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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * Use case: the thread-creation entry points `ForkJoinPool.commonPool`, `CompletableFuture.*Async`,
     * `BaseStream.parallel` and `Collection.parallelStream` are all classified as
     * [SandboxApiCategory.THREAD_CREATION].
     */
    @Test
    fun `parallel execution entry points are guarded as THREAD_CREATION`() {
        assertEquals(
            SandboxApiCategory.THREAD_CREATION,
            guardedCategoryFor("java/util/concurrent/ForkJoinPool", "commonPool", "()Ljava/util/concurrent/ForkJoinPool;"),
        )
        assertEquals(
            SandboxApiCategory.THREAD_CREATION,
            guardedCategoryFor("java/util/concurrent/CompletableFuture", "supplyAsync", "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"),
        )
        assertEquals(
            SandboxApiCategory.THREAD_CREATION,
            guardedCategoryFor("java/util/stream/Stream", "parallel", "()Ljava/util/stream/BaseStream;"),
        )
        assertEquals(
            SandboxApiCategory.THREAD_CREATION,
            guardedCategoryFor("java/util/Collection", "parallelStream", "()Ljava/util/stream/Stream;"),
        )
    }

    /**
     * Use case: non-parallel members of the stream and fork-join owners stay unguarded, so the
     * thread-creation rules match only the curated entry points.
     */
    @Test
    fun `ordinary stream and fork-join members are not guarded`() {
        assertNull(guardedCategoryFor("java/util/stream/Stream", "filter", "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"))
        assertNull(guardedCategoryFor("java/util/concurrent/ForkJoinPool", "getParallelism", "()I"))
    }

    private fun <T> throwingProxy(type: Class<T>, answers: Map<String, Any?> = emptyMap()): T =
        type.cast(
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                if (answers.containsKey(method.name)) answers[method.name] else throw IllegalStateException("boom")
            },
        )

    /**
     * Use case: the [TypePool] fails while describing a non-JDK owner - the failure is swallowed and no
     * category is returned instead of failing the class transformation.
     */
    @Test
    fun `a type pool failing on describe returns null`() {
        val pool = throwingProxy(TypePool::class.java)

        assertNull(guardedCategoryForSubtype("com/example/plugin/Helper", "run", "()V", pool))
    }

    /**
     * Use case: a resolution that reports itself resolved but fails when the type is fetched is
     * swallowed and yields no category.
     */
    @Test
    fun `a resolution failing on resolve returns null`() {
        val resolution = throwingProxy(TypePool.Resolution::class.java, mapOf("isResolved" to true))
        val pool = throwingProxy(TypePool::class.java, mapOf("describe" to resolution))

        assertNull(guardedCategoryForSubtype("com/example/plugin/Helper", "run", "()V", pool))
    }

    /**
     * Use case: a resolved type whose hierarchy check fails (e.g. a missing super type) is treated as
     * not derived from any guarded base type and yields no category.
     */
    @Test
    fun `a type failing the hierarchy check returns null`() {
        val type = throwingProxy(net.bytebuddy.description.type.TypeDescription::class.java)
        val resolution = throwingProxy(TypePool.Resolution::class.java, mapOf("isResolved" to true, "resolve" to type))
        val pool = throwingProxy(TypePool::class.java, mapOf("describe" to resolution))

        assertNull(guardedCategoryForSubtype("com/example/plugin/Helper", "run", "()V", pool))
    }

    private fun transformedClassText(bootstrapArgument: net.bytebuddy.jar.asm.Handle?): String {
        val asm = net.bytebuddy.jar.asm.Opcodes.ASM9
        val className = "com/example/generated/LambdaHolder"
        val writer = net.bytebuddy.jar.asm.ClassWriter(0)
        writer.visit(net.bytebuddy.jar.asm.Opcodes.V1_8, net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        val method = writer.visitMethod(
            net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC or net.bytebuddy.jar.asm.Opcodes.ACC_STATIC,
            "make", "()Ljava/lang/Runnable;", null, null,
        )
        method.visitCode()
        val metafactory = net.bytebuddy.jar.asm.Handle(
            net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false,
        )
        val arguments = mutableListOf<Any>(net.bytebuddy.jar.asm.Type.getMethodType("()V"))
        if (bootstrapArgument != null) arguments += bootstrapArgument
        arguments += net.bytebuddy.jar.asm.Type.getMethodType("()V")
        method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", metafactory, *arguments.toTypedArray())
        method.visitInsn(net.bytebuddy.jar.asm.Opcodes.ARETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        writer.visitEnd()
        check(asm > 0)

        val locator = net.bytebuddy.dynamic.ClassFileLocator.Compound(
            net.bytebuddy.dynamic.ClassFileLocator.Simple.of(className.replace('/', '.'), writer.toByteArray()),
            net.bytebuddy.dynamic.ClassFileLocator.ForClassLoader.ofSystemLoader(),
        )
        val type = TypePool.Default.of(locator).describe(className.replace('/', '.')).resolve()
        val bytes = net.bytebuddy.ByteBuddy().redefine<Any>(type, locator).visit(GuardAsmVisitorWrapper).make().bytes
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * Use case: a method reference to a guarded JDK API (`Files::readAllBytes`) produces no `INVOKE*`
     * instruction, only an `invokedynamic` - the wrapper still injects a guard call for it.
     */
    @Test
    fun `a method reference to a guarded API gets a guard call`() {
        val handle = net.bytebuddy.jar.asm.Handle(
            net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC, "java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B", false,
        )

        assertTrue(transformedClassText(handle).contains("SandboxGuardRegistry"))
    }

    /**
     * Use case: an `invokedynamic` whose method-handle arguments reference only unguarded APIs (and a
     * call site without any handle argument) gets no guard call.
     */
    @Test
    fun `a method reference to an unguarded API gets no guard call`() {
        val handle = net.bytebuddy.jar.asm.Handle(
            net.bytebuddy.jar.asm.Opcodes.H_INVOKEVIRTUAL, "java/lang/String", "length", "()I", false,
        )

        assertFalse(transformedClassText(handle).contains("SandboxGuardRegistry"))
        assertFalse(transformedClassText(null).contains("SandboxGuardRegistry"))
    }

    /** A plugin-defined `java.io.File` subclass, standing in for the subclass-bypass attempt. */
    private class FileSubtype : java.io.File("example.txt")

    /** A plugin-defined `Thread` subclass, standing in for the subclass-bypass attempt. */
    private class ThreadSubtype : Thread()
}
