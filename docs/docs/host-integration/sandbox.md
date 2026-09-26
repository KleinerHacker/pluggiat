# Runtime sandbox

`PluginSandbox` is the host-wide facade for a plugin's *runtime* sandbox - a layer on top of the
pre-load [security chain](security.md) that mediates what an already-loaded plugin is allowed to do
while it runs.

!!! warning "Requires a `-javaagent` JVM start parameter"

    As soon as any configured `PluginSandboxPolicy` restricts at least one API category, the host
    JVM **must** be started with this module's own JAR as a Java agent, or the host aborts with a
    `SandboxAgentNotActiveException` the moment such a policy is activated:

    ```text
    java -javaagent:pluggiat-<version>.jar -jar my-host-app.jar
    ```

    See [Enabling API mediation](#enabling-api-mediation-javaagent) below for details.

## Configuring a policy

```kotlin
val manager = pluginManager {
    defaultSandboxPolicy {
        type = PluginLocationType.EXTERNAL
        policy = PluginSandboxPolicy(
            allowedApiCategories = setOf(SandboxApiCategory.THREAD_CREATION),
        )
    }
    location {
        path = Paths.get("/opt/myapp/plugins")
        type = PluginLocationType.EXTERNAL
        sandboxOverride = PluginSandboxPolicy.UNRESTRICTED // opts this one location back out
    }
}
```

* `sandboxPolicies` (via `defaultSandboxPolicy { type = ...; policy = ... }`) - the default policy
  per `PluginLocationType`.
* A location's own `sandboxOverride` always wins over the default for its `type`.
* No configuration at all (the default) resolves to `PluginSandboxPolicy.UNRESTRICTED` - fully
  permissive, no Java agent required.

`PluginSandboxPolicy.allowedApiCategories` lists the `SandboxApiCategory` values a plugin under this
policy may use directly. Any category *not* in this set is blocked at the guarded call site.

## What each category covers

| Category | Guarded APIs |
|----------|--------------|
| `FILESYSTEM` | `java.io` file types (`File` on *every* member, `FileInputStream`/`FileOutputStream`/`FileReader`/`FileWriter`/`RandomAccessFile`, `FileDescriptor`); the whole `java.nio.file` equivalent (`Files`, `Paths`, `FileSystem`/`FileSystems`, `DirectoryStream`, `WatchService`) plus the `java.nio.file.spi.FileSystemProvider` SPI beneath it; `FileChannel`/`AsynchronousFileChannel`; the filesystem-touching `Path` members (`toRealPath`, `register`, `toFile`); file-opening constructors of `PrintStream`/`PrintWriter`/`Scanner`/`Formatter`; `ZipFile`, `JarFile`, `ImageIO`, `FileHandler` |
| `NETWORK` | `Socket`, `ServerSocket`, `DatagramSocket`, `MulticastSocket` (each on *every* member), `URLConnection`/`HttpURLConnection`/`JarURLConnection`, `URL.openConnection`/`openStream`/`getContent`, `java.net.http.HttpClient`, the `java.nio.channels` network channels and the `java.nio.channels.spi` selector provider beneath them, the `javax.net`/`javax.net.ssl` socket factories, DNS resolution via `InetAddress`, `NetworkInterface`, `java.rmi` and `javax.naming` (JNDI) |
| `REFLECTION` | the entire `java.lang.reflect` and `java.lang.invoke` packages (`Field.get`/`set`/`setAccessible`, `Constructor.newInstance`, `Proxy`, `MethodHandle.invoke`, `VarHandle`, `MethodHandles.privateLookupIn`), the reflective `java.lang.Class` members (`forName`, `getDeclared*`, `getClassLoader`, …), `ClassLoader.defineClass`/`loadClass`, `ObjectInputStream` (deserialization), `sun.misc.Unsafe`/`jdk.internal.misc.Unsafe` |
| `PROCESS_START` | `ProcessBuilder`, `Process`, `ProcessHandle`, `Runtime.exec`/`halt`/`addShutdownHook`, `System.exit`, and native code loading (`System.load`/`loadLibrary`) |
| `THREAD_CREATION` | `Thread` creation/start (including virtual threads), `ThreadGroup`, `Executors`, `ThreadPoolExecutor`/`ScheduledThreadPoolExecutor`/`ForkJoinPool`/`Timer` construction, `ForkJoinPool.commonPool`, the async `CompletableFuture` stages |

Guarding is not limited to the literal types above:

* **Subclasses are covered.** A plugin defining `class MyFile extends File` and calling
  `myFile.delete()` is guarded too - the agent resolves the call site's type hierarchy and applies the
  base type's guard, keeping its member-level distinctions intact (a `Thread` subclass is blocked on
  `start()`, still free to call `currentThread()`).
* **Reflective and indirect reaches are covered.** Both `Method.invoke`-style reflection and method
  *references* (`Files::readAllBytes`, which produce no direct call instruction) are guarded.
* **Harmless members are deliberately left alone**, so a restricted plugin can still do ordinary work:
  `System.out.println`, in-memory `java.io` streams, `Path` arithmetic (`resolve`, `getFileName`),
  `URI`/`URLEncoder`, `Class.getName`, `ClassLoader.getResourceAsStream` (reading a resource from the
  plugin's own JAR), `Thread.currentThread`/`sleep`, `Runtime.availableProcessors`. Because a blocked
  call permanently marks the plugin as `POTENTIAL_ATTACK` (see below), a false positive would be
  unrecoverable - which is why whole packages are only guarded where no harmless member exists.

## Enabling API mediation (`-javaagent`)

Bytecode API mediation is implemented as a Java agent (`PluginSandboxAgent`), because JDK 25 removed
`SecurityManager`/`AccessController` (JEP 486) - there is no in-VM permission mechanism left to build
on. The agent instruments every class loaded by a plugin's own `PluginClassLoader`, inserting a guard
check in front of each risk-bearing JDK call.

This module's own build artifact **is** the agent - its manifest already declares `Premain-Class`/
`Agent-Class`. Point `-javaagent` at whichever JAR your build resolves it to (e.g. the fat/shadow JAR
of your host application, or the resolved dependency JAR directly):

```text
java -javaagent:/path/to/pluggiat-<version>.jar -jar my-host-app.jar
```

Dynamic attachment after JVM start (the Attach API) is deliberately **not** supported - JDK 21+
restricts it by default (JEP 451) and would require the host to additionally accept
`-XX:+EnableDynamicAgentLoading`, trading one required flag for another with fewer guarantees (see
[Restrictions](#restrictions) below).

If a `PluginSandboxPolicy` requiring mediation is activated without the agent installed, the plugin
manager throws `SandboxAgentNotActiveException` right away, aborting startup - this is deliberate: a
host that configured a restrictive policy must find out immediately, not learn later that plugins
ran completely unmediated.

## Sandbox violations and `POTENTIAL_ATTACK`

A blocked call does not just fail silently:

1. It is logged as a `WARN` ("SECURITY WARNING - potential attack: ...").
2. The offending plugin is forcibly unloaded immediately, without its `onDisable`/`onUnload` hooks
   being invoked (a plugin that just attacked the sandbox is not trusted to run any more of its own
   code).
3. Its `PluginManager.scanResults` entry is marked `PluginScanStatus.POTENTIAL_ATTACK` - deliberately
   distinct from `SECURITY_PROBLEM`: the latter is a pre-load finding about a candidate that never
   ran, this one is a post-load, runtime finding about a plugin that already executed.
4. The violation is forwarded to `PluginManagerConfiguration.exceptionHandlingStrategy` as a
   `SandboxViolationException`, so the host is notified.

A `POTENTIAL_ATTACK` candidate can **never** be force-loaded again (`PluginManager.forceLoad` throws
`IllegalStateException`) and can never displace a `LOADED` candidate of the same plugin id via
`IdCollisionResolver` - unlike every other non-`LOADED` status, there is no host override for it.

## Security recommendations

!!! tip "Security recommendations"

    * Start with the most restrictive `allowedApiCategories` your plugins actually need, and widen
      deliberately - not the other way round.
    * Always configure a policy for `EXTERNAL` locations; the unconfigured default
      (`PluginSandboxPolicy.UNRESTRICTED`) grants full access and needs no agent at all.
    * Combine this with a signature-based [security chain](security.md) - the sandbox limits what an
      already-loaded plugin can do, it does not vet who produced it.
    * Treat a `POTENTIAL_ATTACK` status as an incident, not a routine rejection: unlike
      `SECURITY_PROBLEM`, there is deliberately no override - investigate before considering
      re-distribution of that plugin build.
    * Remember the very-early-class-initialization gap below - mediation is strong but not absolute.

## Restrictions

* **Only the plugin's own code is instrumented.** The agent transforms classes loaded by a plugin's
  `PluginClassLoader`. Code the plugin merely *calls* - your host's own SDK classes, anything exposed
  through the [SDK whitelist](sdk-whitelist.md) - is not instrumented, so a host method that performs
  a risky operation on the plugin's behalf is not mediated. Keep the whitelisted surface free of
  methods that take an arbitrary path, URL or class name from the caller.
* **Very early class initialization**: bytecode referencing a risk-bearing JDK class before the agent
  is registered (e.g. in a `<clinit>` that runs during class loading itself) cannot be instrumented
  retroactively.
* **Native code is outside the reach of bytecode instrumentation** entirely. Loading it is therefore
  guarded as `PROCESS_START`, but a plugin permitted to load native code is effectively unsandboxed.
* **Thread/time-limit governance and process isolation** are separate, later parts of the runtime
  sandbox and not covered by API mediation alone - `THREAD_CREATION` blocks *creating* threads, it
  does not bound the runtime of one already running.
