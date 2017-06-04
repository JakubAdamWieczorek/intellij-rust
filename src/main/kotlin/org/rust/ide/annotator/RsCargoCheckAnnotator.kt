package org.rust.ide.annotator

import com.google.gson.JsonParser
import com.intellij.lang.annotation.*
import com.intellij.lang.annotation.Annotation
import com.intellij.openapi.application.Result
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.PathUtil
import org.apache.commons.lang.StringEscapeUtils.escapeHtml
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.toolchain.CargoCode
import org.rust.cargo.toolchain.CargoMessage
import org.rust.cargo.toolchain.CargoSpan
import org.rust.cargo.toolchain.CargoTopMessage
import java.util.*

data class CargoCheckAnnotationInfo(val file: PsiFile, val editor: Editor)

class CargoCheckAnnotationResult(commandOutput: List<String>, val project: Project)
    : ModificationTracker by PsiManager.getInstance(project).modificationTracker {

    companion object {
        private val parser = JsonParser()
        private val messageRegex = """\s*\{\s*"message".*""".toRegex()
    }

    val messages =
        commandOutput
            .filter { messageRegex.matches(it) }
            .map { parser.parse(it) }
            .filter { it.isJsonObject }
            .mapNotNull { CargoTopMessage.fromJson(it.asJsonObject) }
            .filter { !it.message.message.startsWith("aborting due to") }
}

class RsCargoCheckAnnotator : ExternalAnnotator<CargoCheckAnnotationInfo, CargoCheckAnnotationResult>() {

    private fun getCachedResult(file: PsiFile) =
        CachedValuesManager.getManager(file.project).createCachedValue {
            CachedValueProvider.Result.create(
                checkProject(file),
                PsiModificationTracker.MODIFICATION_COUNT)
        }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CargoCheckAnnotationInfo? =
        if (file.project.rustSettings.useCargoCheckAnnotator) {
            CargoCheckAnnotationInfo(file, editor)
        } else null

    override fun doAnnotate(info: CargoCheckAnnotationInfo): CargoCheckAnnotationResult? =
        getCachedResult(info.file).value

    override fun apply(file: PsiFile, annotationResult: CargoCheckAnnotationResult?, holder: AnnotationHolder) {
        annotationResult ?: return
        val doc = holder.currentAnnotationSession.file.viewProvider.document ?: throw AssertionError()

        for ((message) in annotationResult.messages) {
            val filePath = holder.currentAnnotationSession.file.virtualFile.path

            val severity =
                when (message.level) {
                    "error" -> HighlightSeverity.ERROR
                    "warning" -> HighlightSeverity.WEAK_WARNING
                    else -> HighlightSeverity.INFORMATION
                }

            // If spans are empty we add a "global" error
            if (message.spans.isEmpty()) {
//                if (topMessage.target.src_path == filePath) {
//                    // add a global annotation
//                    val annotation = holder.createAnnotation(severity, TextRange.EMPTY_RANGE, message.message)
//                    annotation.isFileLevelAnnotation = true
//                    annotation.setNeedsUpdateOnTyping(true)
//                    annotation.tooltip = escapeHtml(message.message) + " " + message.code.formatAsLink()
//                }
            } else {
                val problemGroup = ProblemGroup { message.message }

                val primarySpan =
                    message.spans
                        .filter { it.is_primary }
                        .filter { isValidSpan(it) }
                        .filter { filePath.endsWith(PathUtil.getCanonicalPath(it.file_name)) }
                        .firstOrNull()

                if (primarySpan != null) {
                    val annotation = createAnnotation(primarySpan, message, severity, doc, holder)
                    annotation.problemGroup = problemGroup
                    annotation.setNeedsUpdateOnTyping(true)
                }
            }
        }
    }

    fun createAnnotation(span: CargoSpan, message: CargoMessage, severity: HighlightSeverity, doc: Document,
                         holder: AnnotationHolder): Annotation {

        fun toOffset(line: Int, column: Int): Int {
            val lineStart = doc.getLineStartOffset(line)
            return lineStart + column
        }

        // The compiler message lines and columns are 1 based while intellij idea are 0 based
        val textRange =
            TextRange(
                toOffset(span.line_start - 1, span.column_start - 1),
                toOffset(span.line_end - 1, span.column_end - 1))

        val tooltip = run {
            val lines = ArrayList<String?>()

            if (span.label != null && message.message.startsWith(span.label))
                lines.add(span.label)

            message.children
                .filter { !it.message.isBlank() }
                .forEach {
                    val prefix = when (it.level) {
                        "note" -> "Note: "
                        "help" -> "Help: "
                        else -> ""
                    }
                    lines.add(prefix + it.message)
                }

            lines.add(message.code.formatAsLink())
            lines.filterNotNull().joinToString("</br>")
        }

        val annotation = holder.createAnnotation(severity, textRange, message.message)
        annotation.tooltip = "<html>${escapeHtml(tooltip)}</html>"
        return annotation
    }

    private val ERROR_INDEX_URL = "https://doc.rust-lang.org/error-index.html"

    fun CargoCode?.formatAsLink() =
        if (this?.code.isNullOrBlank()) ""
        else "<a href=\"$ERROR_INDEX_URL#${this?.code}\">${this?.code}</a>"

    fun isValidSpan(span: CargoSpan) =
        span.line_end > span.line_start
            || (span.line_end == span.line_start && span.column_end >= span.column_start)

    fun checkProject(file: PsiFile): CargoCheckAnnotationResult? {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null

        // We have to save the file to disk to give cargo a chance to check fresh file content.
        object : WriteAction<Unit>() {
            override fun run(result: Result<Unit>) {
                val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)
                if (document != null) {
                    FileDocumentManager.getInstance().saveDocument(document)
                } else {
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            }
        }.execute()

        val moduleDirectory = PathUtil.getParentPath(module.moduleFilePath)
        val output = module.project.toolchain?.cargo(moduleDirectory)?.checkFile(module)
        output ?: return null
        if (output.isCancelled) return null
        return CargoCheckAnnotationResult(output.stdoutLines, file.project)
    }
}
