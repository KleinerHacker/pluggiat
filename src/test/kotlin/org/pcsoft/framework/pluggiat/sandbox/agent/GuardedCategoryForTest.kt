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
import java.io.File

/**
 * Verifies [guardedCategoryFor]'s call-site-to-category mapping and [guardedCategoryForSubtype]'s
 * hierarchy fallback - together the single source of truth [GuardAsmVisitorWrapper] consults to decide
 * which instructions get a guard call inserted in front of them.
 */
class GuardedCategoryForTest {

    /** A plugin-defined `java.io.File` subclass, standing in for the subclass-bypass attempt. */
    private class DerivedFile : File("example.txt")

    /** A plugin-defined `Thread` subclass, standing in for the subclass-bypass attempt. */
    private class DerivedThread : Thread()

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')

    //region FILESYSTEM

    /**
     * Use case: every `java.io.File` method call - not only its constructor - is guarded as
     * [SandboxApiCategory.FILESYSTEM], since the JDK itself can hand out a `File` instance without the
     * plugin's bytecode ever executing a guarded constructor (e.g. `Path.toFile()`).
     */
    @Test
    fun `every File method call is guarded as FILESYSTEM`() {
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/File", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/File", "delete"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/File", "mkdirs"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/File", "listFiles"))
    }

    /**
     * Use case: the remaining `java.io` file types - reader/writer/stream/random-access, plus the
     * `FileDescriptor` handle itself - are guarded as [SandboxApiCategory.FILESYSTEM].
     */
    @Test
    fun `java-io file types are guarded as FILESYSTEM`() {
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/FileInputStream", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/FileOutputStream", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/FileReader", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/FileWriter", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/RandomAccessFile", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/io/FileDescriptor", "sync"))
    }

    /**
     * Use case: in-memory `java.io` types that never touch the filesystem stay unguarded - guarding
     * the `java.io` package wholesale would block `System.out.println` (a `PrintStream` member) and
     * every `ByteArrayOutputStream`, permanently bricking legitimate plugins via `POTENTIAL_ATTACK`.
     */
    @Test
    fun `in-memory java-io types stay unguarded`() {
        assertNull(guardedCategoryFor("java/io/PrintStream", "println"))
        assertNull(guardedCategoryFor("java/io/ByteArrayOutputStream", "<init>"))
        assertNull(guardedCategoryFor("java/io/ByteArrayInputStream", "read"))
        assertNull(guardedCategoryFor("java/io/StringWriter", "toString"))
        assertNull(guardedCategoryFor("java/io/BufferedReader", "readLine"))
    }

    /**
     * Use case: a constructor that opens a *named* resource is guarded as
     * [SandboxApiCategory.FILESYSTEM], while the same constructor over an already-open in-memory
     * sink is not - the distinction is made on the descriptor.
     */
    @Test
    fun `file-opening constructors are guarded by descriptor only`() {
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/lang/String;)V"),
        )
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/io/PrintWriter", "<init>", "(Ljava/io/File;)V"),
        )
        assertNull(guardedCategoryFor("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;)V"))
    }

    /**
     * Use case: `java.nio.file.Files` methods (a filesystem path entirely independent of
     * `java.io.File`), the file channels, and the `java.nio.file` gateways `Paths`/`FileSystems` are
     * all guarded as [SandboxApiCategory.FILESYSTEM].
     */
    @Test
    fun `java-nio filesystem call sites are guarded as FILESYSTEM`() {
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Files", "newInputStream"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Files", "readAllBytes"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Files", "delete"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Paths", "get"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/FileSystems", "newFileSystem"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/channels/FileChannel", "open"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/channels/AsynchronousFileChannel", "open"))
    }

    /**
     * Use case: the `java.nio.file.spi.FileSystemProvider` SPI *beneath* `Files` - reachable via
     * `path.getFileSystem().provider()` and otherwise a complete bypass of every `Files` guard - is
     * guarded as [SandboxApiCategory.FILESYSTEM] through its package prefix.
     */
    @Test
    fun `the FileSystemProvider SPI is guarded as FILESYSTEM`() {
        assertEquals(
            SandboxApiCategory.FILESYSTEM,
            guardedCategoryFor("java/nio/file/spi/FileSystemProvider", "newInputStream"),
        )
    }

    /**
     * Use case: only those `java.nio.file.Path` members that themselves touch the filesystem are
     * guarded; pure path arithmetic stays usable, since a `Path` is a common value type to exchange
     * with the host's own SDK.
     */
    @Test
    fun `only filesystem-touching Path members are guarded`() {
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Path", "toRealPath"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Path", "register"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/nio/file/Path", "toFile"))
        assertNull(guardedCategoryFor("java/nio/file/Path", "getFileName"))
        assertNull(guardedCategoryFor("java/nio/file/Path", "resolve"))
    }

    /**
     * Use case: file-opening utilities outside `java.io`/`java.nio` - archive readers and `ImageIO` -
     * are guarded as [SandboxApiCategory.FILESYSTEM].
     */
    @Test
    fun `archive and image file openers are guarded as FILESYSTEM`() {
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/util/zip/ZipFile", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("java/util/jar/JarFile", "<init>"))
        assertEquals(SandboxApiCategory.FILESYSTEM, guardedCategoryFor("javax/imageio/ImageIO", "read"))
    }

    //endregion

    //region NETWORK

    /**
     * Use case: every `java.net.Socket`/`ServerSocket`/`DatagramSocket` method call - not only their
     * constructor - is guarded as [SandboxApiCategory.NETWORK], since the JDK itself can hand out such
     * an instance without a guarded constructor call (e.g. `SocketChannel.socket()`). `MulticastSocket`
     * needs its own entry: the owner in the bytecode is the static type, not the base class.
     */
    @Test
    fun `every socket type method call is guarded as NETWORK`() {
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/Socket", "<init>"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/Socket", "connect"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/ServerSocket", "accept"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/DatagramSocket", "send"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/MulticastSocket", "joinGroup"))
    }

    /**
     * Use case: `java.nio.channels` network channels, `URL.openConnection`/`openStream`,
     * `URLConnection` itself and `java.net.http.HttpClient` are guarded as
     * [SandboxApiCategory.NETWORK] - each bypasses `java.net.Socket` entirely.
     */
    @Test
    fun `java-nio and java-net-http network call sites are guarded as NETWORK`() {
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/nio/channels/SocketChannel", "open"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/nio/channels/ServerSocketChannel", "open"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/nio/channels/DatagramChannel", "open"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/URL", "openConnection"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/URL", "openStream"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/URLConnection", "getInputStream"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/HttpURLConnection", "connect"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/http/HttpClient", "send"))
    }

    /**
     * Use case: DNS resolution via `InetAddress` is guarded as [SandboxApiCategory.NETWORK] - it is
     * both a network call and a data-exfiltration channel in its own right - and so is local interface
     * enumeration.
     */
    @Test
    fun `DNS resolution and interface enumeration are guarded as NETWORK`() {
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/InetAddress", "getByName"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/InetAddress", "getAllByName"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/net/NetworkInterface", "getNetworkInterfaces"))
    }

    /**
     * Use case: the socket factories in `javax.net` (which hand out a socket without the plugin ever
     * calling a `Socket` constructor), the `java.nio.channels.spi` selector provider (which bypasses
     * `SocketChannel.open`), RMI and JNDI are all guarded as [SandboxApiCategory.NETWORK] through
     * their package prefixes.
     */
    @Test
    fun `socket factories, channel SPI, RMI and JNDI are guarded as NETWORK`() {
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("javax/net/SocketFactory", "createSocket"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("javax/net/ssl/SSLSocketFactory", "createSocket"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/nio/channels/spi/SelectorProvider", "openSocketChannel"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("java/rmi/Naming", "lookup"))
        assertEquals(SandboxApiCategory.NETWORK, guardedCategoryFor("javax/naming/InitialContext", "lookup"))
    }

    /**
     * Use case: `java.net` value types that never reach the network stay unguarded - a plugin may
     * legitimately build a `URI` or escape a query parameter and hand the result to the host.
     */
    @Test
    fun `java-net value types stay unguarded`() {
        assertNull(guardedCategoryFor("java/net/URI", "create"))
        assertNull(guardedCategoryFor("java/net/URLEncoder", "encode"))
        assertNull(guardedCategoryFor("java/net/URL", "getHost"))
    }

    //endregion

    //region PROCESS_START

    /**
     * Use case: `ProcessBuilder`, `Runtime.exec`, `Process`/`ProcessHandle` and `System.exit` are all
     * guarded as [SandboxApiCategory.PROCESS_START] - `Runtime.exec` being the most direct
     * process-start vector of all, and `ProcessHandle.destroy` able to kill the host JVM.
     */
    @Test
    fun `process start and JVM termination are guarded as PROCESS_START`() {
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/ProcessBuilder", "start"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/ProcessBuilder", "startPipeline"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/Runtime", "exec"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/Runtime", "halt"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/System", "exit"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/ProcessHandle", "destroy"))
    }

    /**
     * Use case: loading native code (`System.load`/`loadLibrary`) is guarded as
     * [SandboxApiCategory.PROCESS_START] - native code escapes every bytecode-level guard, so it must
     * not be reachable under a restricted policy.
     */
    @Test
    fun `native library loading is guarded as PROCESS_START`() {
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/System", "loadLibrary"))
        assertEquals(SandboxApiCategory.PROCESS_START, guardedCategoryFor("java/lang/Runtime", "load"))
    }

    /**
     * Use case: harmless `Runtime`/`System` members stay unguarded - a plugin reading the available
     * processor count or the current time is not attacking anything.
     */
    @Test
    fun `harmless Runtime and System members stay unguarded`() {
        assertNull(guardedCategoryFor("java/lang/Runtime", "availableProcessors"))
        assertNull(guardedCategoryFor("java/lang/Runtime", "getRuntime"))
        assertNull(guardedCategoryFor("java/lang/System", "currentTimeMillis"))
    }

    //endregion

    //region REFLECTION

    /**
     * Use case: the whole `java.lang.reflect` package is guarded as [SandboxApiCategory.REFLECTION] -
     * `Field.get`/`set`/`setAccessible` and `Constructor.newInstance` are the core vectors for reading
     * host-internal state (e.g. the persistence HMAC key), and `Proxy.newProxyInstance` for forging an
     * implementation.
     */
    @Test
    fun `the whole java-lang-reflect package is guarded as REFLECTION`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/Method", "invoke"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/Field", "get"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/Field", "set"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/AccessibleObject", "setAccessible"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/Constructor", "newInstance"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/reflect/Proxy", "newProxyInstance"))
    }

    /**
     * Use case: the whole `java.lang.invoke` package is guarded as [SandboxApiCategory.REFLECTION] -
     * guarding only `MethodHandles.Lookup` would leave the actual invocation (`MethodHandle.invoke`),
     * field access (`VarHandle.set`) and the `privateLookupIn` escape hatch unmediated.
     */
    @Test
    fun `the whole java-lang-invoke package is guarded as REFLECTION`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/invoke/MethodHandles\$Lookup", "findVirtual"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/invoke/MethodHandles\$Lookup", "findGetter"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/invoke/MethodHandle", "invokeExact"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/invoke/VarHandle", "set"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/invoke/MethodHandles", "privateLookupIn"))
    }

    /**
     * Use case: `sun.misc.Unsafe` is guarded as [SandboxApiCategory.REFLECTION] - it defeats every
     * bytecode-level guard outright by reading and writing memory directly.
     */
    @Test
    fun `Unsafe is guarded as REFLECTION`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("sun/misc/Unsafe", "getUnsafe"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("jdk/internal/misc/Unsafe", "objectFieldOffset"))
    }

    /**
     * Use case: `java.lang.Class` members that hand out reflective access are guarded as
     * [SandboxApiCategory.REFLECTION], while the descriptive members that ordinary code (logging above
     * all) relies on stay unguarded.
     */
    @Test
    fun `only reflective Class members are guarded`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/Class", "forName"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/Class", "getDeclaredField"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/Class", "getDeclaredMethods"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/Class", "getClassLoader"))
        assertNull(guardedCategoryFor("java/lang/Class", "getName"))
        assertNull(guardedCategoryFor("java/lang/Class", "getSimpleName"))
        assertNull(guardedCategoryFor("java/lang/Class", "isInstance"))
    }

    /**
     * Use case: `ClassLoader.defineClass`/`loadClass` are guarded as [SandboxApiCategory.REFLECTION],
     * while resource loading stays unguarded - reading an icon from the plugin's own JAR is a routine
     * need and is not access to an arbitrary filesystem path.
     */
    @Test
    fun `class definition is guarded but resource loading is not`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/ClassLoader", "defineClass"))
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/lang/ClassLoader", "loadClass"))
        assertNull(guardedCategoryFor("java/lang/ClassLoader", "getResourceAsStream"))
    }

    /**
     * Use case: `ObjectInputStream` is guarded as [SandboxApiCategory.REFLECTION] - deserialization
     * instantiates and invokes arbitrary types, the classic gadget-chain vector.
     */
    @Test
    fun `deserialization is guarded as REFLECTION`() {
        assertEquals(SandboxApiCategory.REFLECTION, guardedCategoryFor("java/io/ObjectInputStream", "readObject"))
    }

    //endregion

    //region THREAD_CREATION

    /**
     * Use case: creating or starting a thread, including a virtual one, is guarded as
     * [SandboxApiCategory.THREAD_CREATION], while the ubiquitous read-only `Thread` members stay
     * unguarded.
     */
    @Test
    fun `thread creation is guarded but thread inspection is not`() {
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/lang/Thread", "<init>"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/lang/Thread", "start"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/lang/Thread", "ofVirtual"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/lang/Thread", "startVirtualThread"))
        assertNull(guardedCategoryFor("java/lang/Thread", "currentThread"))
        assertNull(guardedCategoryFor("java/lang/Thread", "sleep"))
    }

    /**
     * Use case: the executor/pool factories that spawn threads on a plugin's behalf are guarded as
     * [SandboxApiCategory.THREAD_CREATION] - otherwise a plugin could sidestep the `Thread` guard
     * entirely through `Executors` or an async `CompletableFuture` stage.
     */
    @Test
    fun `executor and pool creation is guarded as THREAD_CREATION`() {
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/concurrent/Executors", "newFixedThreadPool"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/concurrent/ThreadPoolExecutor", "<init>"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/concurrent/ForkJoinPool", "commonPool"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/Timer", "<init>"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/concurrent/CompletableFuture", "supplyAsync"))
        assertEquals(SandboxApiCategory.THREAD_CREATION, guardedCategoryFor("java/util/concurrent/CompletableFuture", "thenApplyAsync"))
        assertNull(guardedCategoryFor("java/util/concurrent/CompletableFuture", "completedFuture"))
    }

    //endregion

    //region Hierarchy fallback

    /**
     * Use case: a plugin-defined `java.io.File` subclass cannot escape the filesystem guard by calling
     * an inherited method on its own static type (`derivedFile.delete()` compiles to an owner of the
     * subclass, not of `File`) - [guardedCategoryForSubtype] resolves the hierarchy and applies the
     * base type's guard.
     */
    @Test
    fun `a File subclass inherits the FILESYSTEM guard`() {
        val category = guardedCategoryForSubtype(
            internalName(DerivedFile::class.java), "delete", "()Z", TypePool.Default.ofSystemLoader(),
        )

        assertEquals(SandboxApiCategory.FILESYSTEM, category)
    }

    /**
     * Use case: a subclass inherits the base type's *member-level* curation, not a blanket guard - a
     * `Thread` subclass is guarded on `start` but still free to call `currentThread`, exactly as
     * `java.lang.Thread` itself is.
     */
    @Test
    fun `a Thread subclass inherits the members curation of Thread`() {
        val typePool = TypePool.Default.ofSystemLoader()
        val subclassName = internalName(DerivedThread::class.java)

        assertEquals(
            SandboxApiCategory.THREAD_CREATION,
            guardedCategoryForSubtype(subclassName, "start", "()V", typePool),
        )
        assertNull(guardedCategoryForSubtype(subclassName, "currentThread", "()Ljava/lang/Thread;", typePool))
    }

    /**
     * Use case: an unrelated class is not guarded by the hierarchy fallback, and an unresolvable owner
     * (e.g. a plugin's missing optional dependency) resolves to "not guarded" instead of failing the
     * transformation.
     */
    @Test
    fun `unrelated and unresolvable owners are not guarded by the hierarchy fallback`() {
        val typePool = TypePool.Default.ofSystemLoader()

        assertNull(guardedCategoryForSubtype(internalName(GuardedCategoryForTest::class.java), "toString", "()Ljava/lang/String;", typePool))
        assertNull(guardedCategoryForSubtype("com/example/absent/MissingType", "anything", "()V", typePool))
    }

    //endregion

    /**
     * Use case: an unrelated call site (e.g. a plain `String` method) is not guarded at all.
     */
    @Test
    fun `unrelated call sites are not guarded`() {
        assertNull(guardedCategoryFor("java/lang/String", "length"))
        assertNull(guardedCategoryFor("java/util/ArrayList", "add"))
    }
}
