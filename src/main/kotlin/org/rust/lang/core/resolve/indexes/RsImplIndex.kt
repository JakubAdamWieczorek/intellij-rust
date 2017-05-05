package org.rust.lang.core.resolve.indexes

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.AbstractStubIndex
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.KeyDescriptor
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.stubs.RsFileStub
import org.rust.lang.core.stubs.RsImplItemStub
import org.rust.lang.core.types.RustType
import org.rust.lang.core.types.RustTypeFingerprint
import org.rust.lang.core.types.type

class RsImplIndex : AbstractStubIndex<RustTypeFingerprint, RsImplItem>() {
    override fun getVersion(): Int = RsFileStub.Type.stubVersion
    override fun getKey(): StubIndexKey<RustTypeFingerprint, RsImplItem> = KEY
    override fun getKeyDescriptor(): KeyDescriptor<RustTypeFingerprint> = RustTypeFingerprint.KeyDescriptor

    companion object {
        fun findImpls(project: Project, target: RustType): Collection<RsImplItem> {
            fun doFind(): Collection<RsImplItem> {
                val fingerprint = RustTypeFingerprint.create(target)
                    ?: return emptyList()

                return StubIndex.getElements(
                    KEY,
                    fingerprint,
                    project,
                    GlobalSearchScope.allScope(project),
                    RsImplItem::class.java
                ).filter { impl ->
                    val ty = impl.typeReference?.type
                    // Addition class check is a temporal solution to filter impls for type parameter
                    // with the same name
                    // struct S; impl<S: Tr1> Tr2 for S {}
                    ty != null && ty.javaClass == target.javaClass && ty.canUnifyWith(target, project)
                }
            }

            val implsCache = CachedValuesManager.getManager(project)
                .getCachedValue(project, {
                    CachedValueProvider.Result.create(
                        ContainerUtil.newConcurrentMap<RustType, Collection<RsImplItem>>(),
                        PsiModificationTracker.MODIFICATION_COUNT
                    )
                })

            return implsCache.getOrPut(target) { doFind() }
        }

        fun index(stub: RsImplItemStub, sink: IndexSink) {
            val type = stub.psi.typeReference ?: return
            val key = RustTypeFingerprint.create(type)
            if (key != null) {
                sink.occurrence(KEY, key)
            }
        }

        private val KEY: StubIndexKey<RustTypeFingerprint, RsImplItem> =
            StubIndexKey.createIndexKey("org.rust.lang.core.stubs.index.RustImplIndex.TraitImpls")
    }
}
