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

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.pool.TypePool
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory

/**
 * Maps a single call-site instruction (owner/name/descriptor of an `INVOKE*` instruction, or of a
 * method-reference target handle) to the [SandboxApiCategory] it belongs to, `null` if the call is
 * not guarded. This is the single source of truth for what the sandbox mediates.
 *
 * ## Granularity: whole type vs. individual members
 *
 * A type is guarded on **every** method ([FILESYSTEM_TYPES], [NETWORK_TYPES], [PROCESS_TYPES],
 * [REFLECTION_TYPES]) whenever it exists solely to perform the risky operation, or whenever the JDK
 * itself can hand a plugin an instance of it without the plugin's own bytecode ever executing a
 * guarded constructor call - `Path.toFile()` yields a `File`, `SocketChannel.socket()` yields a
 * `Socket`, `ServerSocketChannel.accept()` yields a `Socket`. Guarding only `<init>` there would leave
 * every operation on such an instance unmediated.
 *
 * Types that *mix* risky and harmless members are curated member by member instead ([java.lang.Class],
 * [java.lang.System], [java.lang.Runtime], [java.lang.Thread], [java.nio.file.Path], [java.net.URL],
 * [java.lang.ClassLoader]) - blocking `System.out.println`, `Thread.currentThread()`,
 * `Path.getFileName()` or `ClassLoader.getResourceAsStream` would break legitimate plugins for no
 * security gain. Since a blocked call is irreversible for the plugin (see
 * [org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.POTENTIAL_ATTACK] - no force-load possible),
 * a false positive is *more* damaging here than in a typical access-control layer, which is why whole
 * *packages* are only guarded where no harmless member exists at all ([GUARDED_PACKAGE_PREFIXES]).
 *
 * ## Category assignments that are not one-to-one
 *
 * * `System.exit`/`Runtime.halt` are grouped under [SandboxApiCategory.PROCESS_START] rather than
 *   getting a category of their own - halting the host JVM is at least as disruptive as starting an
 *   external process.
 * * `System.load`/`loadLibrary` (native code, which escapes every bytecode-level guard) are likewise
 *   [SandboxApiCategory.PROCESS_START].
 * * `ObjectInputStream` is [SandboxApiCategory.REFLECTION]: deserialization instantiates and invokes
 *   arbitrary types, which is reflection by another name (the classic gadget-chain vector).
 * * DNS resolution (`InetAddress`) is [SandboxApiCategory.NETWORK] - besides being a network call it
 *   is a data-exfiltration channel in its own right.
 */
internal fun guardedCategoryFor(owner: String, name: String, descriptor: String = ""): SandboxApiCategory? = when {
    //region FILESYSTEM
    owner in FILESYSTEM_TYPES -> SandboxApiCategory.FILESYSTEM
    owner == "java/nio/file/Path" && name in PATH_FILESYSTEM_METHODS -> SandboxApiCategory.FILESYSTEM
    owner in FILE_OPENING_TYPES && name == "<init>" && opensNamedResource(descriptor) -> SandboxApiCategory.FILESYSTEM
    //endregion

    //region NETWORK
    owner in NETWORK_TYPES -> SandboxApiCategory.NETWORK
    owner == "java/net/URL" && name in URL_NETWORK_METHODS -> SandboxApiCategory.NETWORK
    //endregion

    //region PROCESS_START
    owner in PROCESS_TYPES -> SandboxApiCategory.PROCESS_START
    owner == "java/lang/Runtime" && name in RUNTIME_PROCESS_METHODS -> SandboxApiCategory.PROCESS_START
    owner == "java/lang/System" && name in SYSTEM_PROCESS_METHODS -> SandboxApiCategory.PROCESS_START
    //endregion

    //region REFLECTION
    owner in REFLECTION_TYPES -> SandboxApiCategory.REFLECTION
    owner == "java/lang/Class" && isClassReflectionMethod(name) -> SandboxApiCategory.REFLECTION
    owner == "java/lang/ClassLoader" && name in CLASS_LOADER_REFLECTION_METHODS -> SandboxApiCategory.REFLECTION
    //endregion

    //region THREAD_CREATION
    owner in THREAD_CREATION_TYPES -> SandboxApiCategory.THREAD_CREATION
    owner == "java/lang/Thread" && name in THREAD_CREATION_METHODS -> SandboxApiCategory.THREAD_CREATION
    owner in THREAD_POOL_TYPES && name == "<init>" -> SandboxApiCategory.THREAD_CREATION
    owner == "java/util/concurrent/ForkJoinPool" && name == "commonPool" -> SandboxApiCategory.THREAD_CREATION
    owner == "java/util/concurrent/CompletableFuture" && name.endsWith("Async") -> SandboxApiCategory.THREAD_CREATION
    //endregion

    // Checked last so that the member-level curation above always wins over a package prefix.
    else -> GUARDED_PACKAGE_PREFIXES.entries.firstOrNull { owner.startsWith(it.key) }?.value
}

/**
 * The [SandboxApiCategory] for [owner] reached through its *supertypes*, `null` if none applies -
 * the structural complement to [guardedCategoryFor], which can only ever match the **static** type
 * written at the call site.
 *
 * Without this, two bypasses stay wide open: a plugin declaring `class MyFile : File(...)` and calling
 * `myFile.delete()` compiles to an owner of `MyFile`, and a third-party library's own `Socket`
 * subclass is equally invisible to a name-based table. Resolving [owner] through [typePool] and
 * re-running [guardedCategoryFor] against the matched base type keeps the member-level curation of
 * that base type intact - `MyThread.start()` is guarded, `MyThread.sleep()` is not, exactly as for
 * [java.lang.Thread] itself.
 *
 * Owners in the JDK's own namespaces are skipped: every relevant JDK subtype is already listed
 * explicitly or covered by a package prefix (e.g. `javax.net.ssl.SSLSocket` via `javax/net/`), so
 * resolving them would only cost transform time. Resolution failures are swallowed - an unresolvable
 * type (a missing optional dependency of the plugin) must not fail the transformation, and a type
 * that cannot be resolved cannot be instantiated by the plugin either.
 *
 * Deliberately **not** memoized across calls: an `owner` string is only unique per class loader, so a
 * process-wide cache would let one plugin's `com.acme.Helper` decide whether another plugin's
 * identically named class is guarded - a false negative, i.e. a security hole. Byte Buddy's per-loader
 * [TypePool] already caches resolutions.
 */
internal fun guardedCategoryForSubtype(
    owner: String,
    name: String,
    descriptor: String,
    typePool: TypePool,
): SandboxApiCategory? {
    if (owner.startsWith("[") || JDK_NAMESPACE_PREFIXES.any { owner.startsWith(it) }) return null
    val baseType = resolveGuardedBaseType(owner, typePool) ?: return null
    return guardedCategoryFor(Type.getInternalName(baseType), name, descriptor)
}

private fun resolveGuardedBaseType(owner: String, typePool: TypePool): Class<*>? {
    val resolution = runCatching { typePool.describe(owner.replace('/', '.')) }.getOrNull() ?: return null
    if (!resolution.isResolved) return null
    val type = runCatching { resolution.resolve() }.getOrNull() ?: return null
    return HIERARCHY_BASE_TYPES.firstOrNull { base ->
        runCatching { type.isAssignableTo(base) }.getOrDefault(false)
    }
}

/**
 * Base types whose subtypes inherit their guard, see [guardedCategoryForSubtype]. Only types a plugin
 * (or a library it bundles) can meaningfully subclass are listed - `java.nio.file.Files` and the other
 * static-only utilities have no instances to inherit anything.
 */
private val HIERARCHY_BASE_TYPES: List<Class<*>> = listOf(
    java.io.File::class.java,
    java.io.ObjectInputStream::class.java,
    java.nio.file.Path::class.java,
    java.nio.channels.FileChannel::class.java,
    java.net.Socket::class.java,
    java.net.ServerSocket::class.java,
    java.net.DatagramSocket::class.java,
    java.net.URLConnection::class.java,
    Thread::class.java,
    ClassLoader::class.java,
)

/** Namespaces whose members are already covered exhaustively by the explicit tables below. */
private val JDK_NAMESPACE_PREFIXES = listOf("java/", "javax/", "jdk/", "sun/", "com/sun/")

//region FILESYSTEM

/**
 * Types guarded as [SandboxApiCategory.FILESYSTEM] on every member. Covers the classic `java.io`
 * file types, the whole `java.nio.file`/`java.nio.channels` equivalent (which bypasses `java.io.File`
 * entirely), and the file-opening utilities that live outside both (`ZipFile`, `JarFile`, `ImageIO`,
 * `FileHandler`).
 */
private val FILESYSTEM_TYPES = setOf(
    "java/io/File",
    "java/io/FileInputStream",
    "java/io/FileOutputStream",
    "java/io/FileReader",
    "java/io/FileWriter",
    "java/io/RandomAccessFile",
    "java/io/FileDescriptor",
    "java/nio/file/Files",
    "java/nio/file/Paths",
    "java/nio/file/FileSystem",
    "java/nio/file/FileSystems",
    "java/nio/file/FileStore",
    "java/nio/file/DirectoryStream",
    "java/nio/file/WatchService",
    "java/nio/channels/FileChannel",
    "java/nio/channels/AsynchronousFileChannel",
    "java/util/zip/ZipFile",
    "java/util/jar/JarFile",
    "java/util/logging/FileHandler",
    "javax/imageio/ImageIO",
)

/**
 * `java.nio.file.Path` members that touch the filesystem themselves - `toRealPath` resolves symlinks,
 * `register` subscribes to a `WatchService`, `toFile` hands out a `java.io.File` for the host or for
 * non-instrumented code to operate on. Pure path arithmetic (`resolve`, `getFileName`, `toString`)
 * stays unguarded: it never reaches the filesystem, and a `Path` is a common value type to exchange
 * with the host's own SDK.
 */
private val PATH_FILESYSTEM_METHODS = setOf("toRealPath", "register", "toFile")

/**
 * Types whose constructor opens a named file in *some* overloads only - guarded via
 * [opensNamedResource] so that `PrintStream(OutputStream)` or `Scanner(String)` over an in-memory
 * source stays usable. `System.out.println` is unaffected either way: only `<init>` is considered.
 */
private val FILE_OPENING_TYPES = setOf(
    "java/io/PrintStream",
    "java/io/PrintWriter",
    "java/util/Scanner",
    "java/util/Formatter",
)

/**
 * Whether [descriptor] names a file rather than an already-open in-memory sink/source. `Scanner` is
 * the one type where a bare `String` means *content*, not a file name, but treating that overload as
 * a filesystem access is the safe direction to err in for a single, rarely used constructor.
 */
private fun opensNamedResource(descriptor: String): Boolean =
    "Ljava/io/File;" in descriptor || "Ljava/nio/file/Path;" in descriptor || "Ljava/lang/String;" in descriptor

//endregion

//region NETWORK

/**
 * Types guarded as [SandboxApiCategory.NETWORK] on every member: the `java.net` socket types
 * (`MulticastSocket` listed separately from `DatagramSocket` - the owner in the bytecode is the static
 * type, so the subclass needs its own entry), `URLConnection` and its subclasses, DNS resolution via
 * `InetAddress`, local interface enumeration, the `java.net.http` client and the `java.nio.channels`
 * network channels that bypass `java.net.Socket` entirely.
 */
private val NETWORK_TYPES = setOf(
    "java/net/Socket",
    "java/net/ServerSocket",
    "java/net/DatagramSocket",
    "java/net/MulticastSocket",
    "java/net/URLConnection",
    "java/net/HttpURLConnection",
    "java/net/JarURLConnection",
    "java/net/InetAddress",
    "java/net/Inet4Address",
    "java/net/Inet6Address",
    "java/net/NetworkInterface",
    "java/net/http/HttpClient",
    "java/nio/channels/SocketChannel",
    "java/nio/channels/ServerSocketChannel",
    "java/nio/channels/DatagramChannel",
    "java/nio/channels/AsynchronousSocketChannel",
    "java/nio/channels/AsynchronousServerSocketChannel",
)

/**
 * `java.net.URL` members that open a connection. Constructing a `URL` or reading its parts is left
 * alone - a `URL` is a value a plugin may legitimately hand to the host.
 */
private val URL_NETWORK_METHODS = setOf("openConnection", "openStream", "getContent")

//endregion

//region PROCESS_START

/** Types guarded as [SandboxApiCategory.PROCESS_START] on every member. */
private val PROCESS_TYPES = setOf(
    "java/lang/ProcessBuilder",
    "java/lang/Process",
    "java/lang/ProcessHandle",
)

/**
 * `java.lang.Runtime` members that start a process, halt the JVM or load native code. `availableProcessors`,
 * `totalMemory` and friends stay unguarded.
 */
private val RUNTIME_PROCESS_METHODS = setOf("exec", "halt", "exit", "addShutdownHook", "load", "loadLibrary")

/** `java.lang.System` members that halt the JVM or load native code. */
private val SYSTEM_PROCESS_METHODS = setOf("exit", "load", "loadLibrary")

//endregion

//region REFLECTION

/**
 * Types guarded as [SandboxApiCategory.REFLECTION] on every member: `MethodHandles.Lookup` (a pure
 * reflection gateway, so enumerating its `find*`/`unreflect*` members would only risk missing one),
 * and `ObjectInputStream`, whose `readObject` instantiates and invokes arbitrary types.
 */
private val REFLECTION_TYPES = setOf(
    "java/lang/invoke/MethodHandles\$Lookup",
    "java/io/ObjectInputStream",
)

/**
 * Whether [name] is a `java.lang.Class` member that hands out reflective access. `getName`,
 * `getSimpleName`, `isInstance`, `getResourceAsStream` and the like stay unguarded - they are common
 * in ordinary code (logging above all) and grant nothing on their own.
 */
private fun isClassReflectionMethod(name: String): Boolean =
    name.startsWith("getDeclared") || name in CLASS_REFLECTION_METHODS

private val CLASS_REFLECTION_METHODS = setOf(
    "forName", "newInstance", "getClassLoader", "getProtectionDomain",
    "getField", "getFields", "getMethod", "getMethods", "getConstructor", "getConstructors",
)

/**
 * `java.lang.ClassLoader` members that define or resolve classes, or reach another loader.
 * `getResource`/`getResourceAsStream` are deliberately absent: reading a resource from the plugin's
 * own JAR is a routine, harmless need and is not filesystem access to an arbitrary path.
 */
private val CLASS_LOADER_REFLECTION_METHODS = setOf(
    "defineClass", "loadClass", "getSystemClassLoader", "getPlatformClassLoader", "getParent",
)

//endregion

//region THREAD_CREATION

/** Types guarded as [SandboxApiCategory.THREAD_CREATION] on every member. */
private val THREAD_CREATION_TYPES = setOf(
    "java/util/concurrent/Executors",
    "java/lang/ThreadGroup",
)

/**
 * `java.lang.Thread` members that create or start a thread. `currentThread`, `sleep`, `interrupt`,
 * `getName` and the like stay unguarded - they are ubiquitous and create nothing.
 */
private val THREAD_CREATION_METHODS = setOf("<init>", "start", "ofVirtual", "ofPlatform", "startVirtualThread")

/** Pool/timer types whose construction spawns threads. */
private val THREAD_POOL_TYPES = setOf(
    "java/util/concurrent/ThreadPoolExecutor",
    "java/util/concurrent/ScheduledThreadPoolExecutor",
    "java/util/concurrent/ForkJoinPool",
    "java/util/Timer",
)

//endregion

/**
 * Packages guarded wholesale, because every type in them exists purely to perform the mapped
 * operation - there is no harmless member to carve out, and enumerating the types would only risk
 * missing one:
 *
 * * `java/lang/reflect/` and `java/lang/invoke/` - `Field.setAccessible`/`get`/`set`,
 *   `Constructor.newInstance`, `Proxy.newProxyInstance`, `MethodHandle.invoke`, `VarHandle.set`,
 *   `MethodHandles.privateLookupIn`: the whole point of both packages is reflective access. Ordinary
 *   Kotlin/Java code never emits method instructions into them - lambdas and string concatenation go
 *   through `invokedynamic`, whose *bootstrap* handle is deliberately not inspected (see
 *   [GuardMethodVisitor.visitInvokeDynamicInsn]).
 * * `sun/misc/` and `jdk/internal/misc/` - `Unsafe` defeats every bytecode-level guard outright.
 * * `java/nio/file/spi/` - `FileSystemProvider` is the SPI *beneath* `Files`; reachable via
 *   `path.getFileSystem().provider()` and would otherwise bypass every filesystem entry above.
 * * `java/nio/channels/spi/` - `SelectorProvider.openSocketChannel()` likewise bypasses
 *   `SocketChannel.open`.
 * * `javax/net/` - `SocketFactory`/`SSLSocketFactory.createSocket()` hands out a socket without the
 *   plugin ever calling a `Socket` constructor.
 * * `java/rmi/` and `javax/naming/` - remote invocation and JNDI lookups (the latter being the
 *   remote-class-loading vector behind Log4Shell) are network access plus remote code loading.
 *
 * Checked after the explicit tables so that member-level curation always wins over a prefix.
 */
private val GUARDED_PACKAGE_PREFIXES: Map<String, SandboxApiCategory> = mapOf(
    "java/lang/reflect/" to SandboxApiCategory.REFLECTION,
    "java/lang/invoke/" to SandboxApiCategory.REFLECTION,
    "sun/misc/" to SandboxApiCategory.REFLECTION,
    "jdk/internal/misc/" to SandboxApiCategory.REFLECTION,
    "java/nio/file/spi/" to SandboxApiCategory.FILESYSTEM,
    "java/nio/channels/spi/" to SandboxApiCategory.NETWORK,
    "javax/net/" to SandboxApiCategory.NETWORK,
    "java/rmi/" to SandboxApiCategory.NETWORK,
    "javax/naming/" to SandboxApiCategory.NETWORK,
)

private val SANDBOX_GUARD_REGISTRY_NAME = SandboxGuardRegistry::class.java.name.replace('.', '/')
private val SANDBOX_API_CATEGORY_NAME = SandboxApiCategory::class.java.name.replace('.', '/')
private val SANDBOX_API_CATEGORY_DESCRIPTOR = "L$SANDBOX_API_CATEGORY_NAME;"

/**
 * Rewrites every method of an instrumented plugin class so that each call site matched by
 * [guardedCategoryFor] or [guardedCategoryForSubtype] is preceded by a call to
 * [SandboxGuardRegistry.check] - a plain [ClassVisitor]/[MethodVisitor] pair operating on Byte Buddy's
 * shaded, bundled ASM (no separate ASM dependency needed, see `dependencies.md`).
 *
 * [SandboxGuardRegistry.check] either returns normally (call allowed) or throws
 * [SandboxViolationException] (call blocked) - in both cases the original instruction is emitted
 * unchanged right after the guard call, so a permitted call behaves exactly as before. The injected
 * sequence is stack-neutral (it pushes two operands and consumes both), so no frame or max-stack
 * recomputation beyond ASM's own is required.
 */
internal object GuardAsmVisitorWrapper : AsmVisitorWrapper.AbstractBase() {
    override fun wrap(
        instrumentedType: TypeDescription,
        classVisitor: ClassVisitor,
        implementationContext: Implementation.Context,
        typePool: TypePool,
        fields: FieldList<FieldDescription.InDefinedShape>,
        methods: MethodList<*>,
        writerFlags: Int,
        readerFlags: Int,
    ): ClassVisitor = GuardClassVisitor(classVisitor, instrumentedType.internalName, typePool)
}

private class GuardClassVisitor(
    classVisitor: ClassVisitor,
    private val ownerInternalName: String,
    private val typePool: TypePool,
) : ClassVisitor(Opcodes.ASM9, classVisitor) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return GuardMethodVisitor(delegate, ownerInternalName, typePool)
    }
}

private class GuardMethodVisitor(
    methodVisitor: MethodVisitor,
    private val ownerInternalName: String,
    private val typePool: TypePool,
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        emitGuard(categoryFor(owner, name, descriptor))
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    /**
     * Guards a method *reference* to a risky API (`Files::readAllBytes`), which never produces an
     * `INVOKE*` instruction in the plugin's own bytecode: the JDK-generated call-site implementation
     * invokes the target handle directly, so [visitMethodInsn] never sees it. Guarding where the call
     * site is *created* is conservative (the reference cannot be obtained at all) and closes the hole.
     *
     * Only [bootstrapMethodArguments] are inspected, never [bootstrapMethodHandle] itself: the latter
     * is `LambdaMetafactory.metafactory` or `StringConcatFactory.makeConcatWithConstants` for every
     * lambda and every string concatenation, both of which live in the wholesale-guarded
     * `java/lang/invoke/` package - checking it would flag ordinary code as a reflection attack.
     * A lambda *body* needs no handling here at all: it compiles to a synthetic method of the plugin
     * class, which is instrumented like any other.
     */
    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        val category = bootstrapMethodArguments
            .filterIsInstance<Handle>()
            .firstNotNullOfOrNull { categoryFor(it.owner, it.name, it.desc) }
        emitGuard(category)
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    private fun categoryFor(owner: String, name: String, descriptor: String): SandboxApiCategory? =
        guardedCategoryFor(owner, name, descriptor)
            ?: guardedCategoryForSubtype(owner, name, descriptor, typePool)

    private fun emitGuard(category: SandboxApiCategory?) {
        if (category == null) return
        super.visitLdcInsn(Type.getObjectType(ownerInternalName))
        super.visitFieldInsn(Opcodes.GETSTATIC, SANDBOX_API_CATEGORY_NAME, category.name, SANDBOX_API_CATEGORY_DESCRIPTOR)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            SANDBOX_GUARD_REGISTRY_NAME,
            "check",
            "(Ljava/lang/Class;$SANDBOX_API_CATEGORY_DESCRIPTOR)V",
            false,
        )
    }
}
