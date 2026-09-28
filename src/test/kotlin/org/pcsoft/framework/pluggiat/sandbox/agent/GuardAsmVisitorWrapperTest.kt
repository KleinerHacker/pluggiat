/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

package org.pcsoft.framework.pluggiat.sandbox.agent

import net.bytebuddy.pool.TypePool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory

/**
 * Verifies the edge cases of [guardedCategoryForSubtype]'s owner pre-checks and resolution failure
 * handling, and the descriptor-based matching behind [guardedCategoryFor]'s file-opening constructors -
 * complementing [GuardedCategoryForTest], which covers the happy-path category tables.
 */
class GuardAsmVisitorWrapperTest {

    /**
     * Use case: an array owner (e.g. the owner of a call site on `Object[]`) is never resolved through
     * the [TypePool] - [guardedCategoryForSubtype] rejects it upfront and returns `null`.
     */
    @Test
    fun `an array owner is never resolved and returns null`() {
        val category = guardedCategoryForSubtype("[Ljava/lang/Object;", "clone", "()Ljava/lang/Object;", TypePool.Default.ofSystemLoader())

        assertNull(category)
    }

    /**
     * Use case: an owner in a JDK namespace is skipped by [guardedCategoryForSubtype] even though it
     * would otherwise resolve fine - every relevant JDK subtype is already covered by the explicit
     * tables or a package prefix, so resolving it again would only cost transform time.
     */
    @Test
    fun `a JDK namespace owner is skipped without resolution`() {
        val category = guardedCategoryForSubtype("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", TypePool.Default.ofSystemLoader())

        assertNull(category)
    }

    /**
     * Use case: an owner that is neither an array nor a JDK namespace, but that the [TypePool] cannot
     * resolve at all (a plugin's missing optional dependency), returns `null` instead of failing the
     * transformation - the resolution failure is swallowed by `resolveGuardedBaseType`'s catch.
     */
    @Test
    fun `an unresolvable non-JDK owner returns null instead of failing`() {
        val category = guardedCategoryForSubtype("com/example/absent/BogusType", "doSomething", "()V", TypePool.Default.ofSystemLoader())

        assertNull(category)
    }

    /**
     * Use case: a constructed subtype of `java.io.File` calling a guarded member resolves to the
     * guarded base type's [SandboxApiCategory.FILESYSTEM] category via [guardedCategoryForSubtype].
     */
    @Test
    fun `a File subtype resolves to the FILESYSTEM category via the TypePool`() {
        val typePool = TypePool.Default.ofSystemLoader()
        val subtypeOwner = FileSubtype::class.java.name.replace('.', '/')

        val category = guardedCategoryForSubtype(subtypeOwner, "delete", "()Z", typePool)

        assertEquals(SandboxApiCategory.FILESYSTEM, category)
    }

    /**
     * Use case: a constructed subtype of `Thread` calling a guarded member resolves to the guarded
     * base type's [SandboxApiCategory.THREAD_CREATION] category via [guardedCategoryForSubtype].
     */
    @Test
    fun `a Thread subtype resolves to the THREAD_CREATION category via the TypePool`() {
        val typePool = TypePool.Default.ofSystemLoader()
        val subtypeOwner = ThreadSubtype::class.java.name.replace('.', '/')

        val category = guardedCategoryForSubtype(subtypeOwner, "start", "()V", typePool)

        assertEquals(SandboxApiCategory.THREAD_CREATION, category)
    }

    /**
     * Use case: each of the three descriptor shapes that [guardedCategoryFor] treats as opening a
     * *named* resource (`File`, `Path`, `String`) is individually guarded as
     * [SandboxApiCategory.FILESYSTEM] for a `FILE_OPENING_TYPES` constructor, hitting every side of the
     * `opensNamedResource` `||` chain.
     */
    @Test
    fun `each named-resource descriptor variant is guarded on a file-opening constructor`() {
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/io/File;)V"),
        )
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)V"),
        )
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/lang/String;)V"),
        )
    }

    /**
     * Use case: a `FILE_OPENING_TYPES` constructor over an already-open in-memory sink (an
     * `OutputStream`, not a `File`/`Path`/`String`) does not match any side of the `opensNamedResource`
     * `||` chain and stays unguarded.
     */
    @Test
    fun `an in-memory sink descriptor does not match the named-resource check`() {
        assertNull(guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;Z)V"))
    }

    /** A plugin-defined `java.io.File` subclass, standing in for the subclass-bypass attempt. */
    private class FileSubtype : java.io.File("example.txt")

    /** A plugin-defined `Thread` subclass, standing in for the subclass-bypass attempt. */
    private class ThreadSubtype : Thread()
}
