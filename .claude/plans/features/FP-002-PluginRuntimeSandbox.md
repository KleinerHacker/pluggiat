# Feature Plan: Plugin Runtime Sandbox (COMPLETED)

## 1. Objective

The existing security chain (`PluginSecurity`, `SignatureSecurityStrategy`,
`ChecksumSecurityStrategy`) checks only the origin and integrity of a plugin candidate *before*
loading. Once a plugin is loaded, it has unrestricted access via `PluginClassLoader` to everything
reachable through the SDK whitelist and the Java platform class loader - including `java.io`,
`java.net`, `java.lang.reflect`, `Runtime.exec`, unlimited thread creation, etc. The goal of this
feature is a **runtime sandbox** that restricts the behaviour of an already loaded plugin, as a
complement to the existing upfront check - including protection against a plugin (or a third party
with file system access) manipulating the framework's persistent state in its own favour, as well
as a hardening of the checksum/signature check itself (gap between check and load, consistency of
the ZIP/JAR interpretation, certificate validity).

## 2. Current State

* `PluginClassLoader` (parent-last, `URLClassLoader`) isolates plugins only with regard to class
  visibility: platform classes are always resolved, SDK whitelist packages are delegated to the
  `hostClassLoader`, everything else comes from the plugin's own JARs or the dependency class
  loaders. There is no access control on concrete APIs (file system, network, reflection, process
  start, threads).
* `PluginLoader.load()` creates the class loader and returns a `LoadedPlugin`; no instrumentation
  or runtime monitoring takes place.
* `PluginManager` manages the lifecycle (`scan`, `reload`, `unload`, `forceLoad`) and calls
  `PluginLifecycle.onEnable/onDisable/onUnload` on the real extension instances
  (`ExtensionAggregator`), but without a time limit or error isolation beyond a simple
  `runCatching`.
* `PluginManager` already holds a counterpart pattern for security checks: `val security:
  PluginSecurity` is the single entry point for the entire security chain
  (`PluginManager.kt:215`). This pattern is the template for the new sandbox facade.
* **Gap between security check and loading (TOCTOU)**: `PluginScanner.applySecurityCheck`
  (`PluginScanner.kt:52`) computes the checksum/signature check of a candidate at scan time, with
  the respective `PluginSecurityStrategy` itself reading the candidate bytes from disk
  (`ChecksumSecurityStrategy.candidateBytes`, `SignatureSecurityStrategy`'s `JarFile` access). The
  actual loading happens separately and later: `PluginManager.scan()` or
  `PluginManager.reactivate()` call `loader.load(scanResult.path, ...)`, and `PluginLoader` reads
  the JAR(s) via `URLClassLoader`/`Files.newDirectoryStream` (`PluginLoader.kt:79`) **again, freshly
  from disk**, without a renewed check. Between the hash/signature check and the actual loading
  there is thus a time window in which the candidate on disk could be swapped without the loaded
  content matching the checked one.
* `ChecksumSecurityStrategy.check()` compares the expected and the computed digest via
  `String.equals(ignoreCase = true)` (`ChecksumSecurityStrategy.kt:53`) instead of a constant-time
  comparison.
* `SignatureSecurityStrategy.verifyJarSignature()` (`SignatureSecurityStrategy.kt:131`) compares
  only the public key of a code signer (`signer.signerCertPath
  .certificates.firstOrNull()?.publicKey == expectedKey`). Neither the validity period
  (`notBefore`/`notAfter`) nor revocation (CRL/OCSP) of the certificate is checked - an expired or
  (where relevant) revoked certificate is currently accepted just like a valid one, as long as the
  plain key value matches.
* The signature check reads JAR entries today via `java.util.jar.JarFile`, while `PluginLoader`
  reads them via `URLClassLoader` - both use `java.util.zip` internally and have so far been
  consistent in their interpretation of ZIP entry names. A planned change (byte pinning, see below)
  introduces a third, independent ZIP/JAR parser which, without explicit coordination, could
  deviate from this interpretation and thereby newly introduce a "verify one entry, load another"
  gap for duplicate ZIP entry names.
* `ExceptionHandlingStrategy` (`DefaultExceptionHandlingStrategy`) handles exceptions from
  extension code, but not actively running threads, infinite loops or resource consumption.
* The build target is `jvmToolchain(25)` (see `build.gradle.kts`). The classic
  `java.lang.SecurityManager`/`AccessController` mechanism has been deprecated since JDK 17 and
  completely removed since JDK 24 (JEP 486) - it is **not** available as a basis for an in-VM
  permission sandbox.
* Dynamically loading a Java agent at runtime (Attach API) has been restricted by default since
  JDK 21 (JEP 451) and, without explicit enabling, produces warnings or is refused - on the target
  JDK 25 a Java agent is therefore no longer a transparent, purely library-side mechanism, but
  requires a deliberate host configuration.
* There is no process or module isolation; all plugins run in the same JVM process as the host.
* The JDK offers no public API for ASN.1 encoding/decoding (only internal `sun.security` classes
  that cannot be used within the project). The user has specified using the third-party library
  **Bouncy Castle** (`org.bouncycastle:bcprov-jdk18on`, classes such as
  `ASN1InputStream`/`ASN1OutputStream`/`DERSequence` etc.) for ASN.1 encoding/decoding; this is
  the user agreement required by `dependencies.md` for this new third-party dependency.
* `PluginPersistenceStrategy` (`PluginPersistenceStrategy.kt:24`) is a generic, unprotected
  key-value store (`read`/`write` per `(pluginId, key)`) without any access control or integrity
  check. `FilePersistenceStrategy` (`FilePersistenceStrategy.kt:37`) writes the entire state
  unencrypted/unsigned into a single file. As long as a plugin has unrestricted `java.io` access,
  it can edit this file directly and, for example, forge its own `checksum`, `securityException` or
  `enabled` entry, thereby overriding the entire security chain for itself.
* **Collision resolution ignores the security/scan status**: `IdCollisionResolver.resolve()`
  (`IdCollisionResolver.kt:40`) groups all candidates with the same `manifest.id` **regardless of
  their scan status** (`withManifest = results.filter { it.manifest != null }`, without a status
  filter) - a candidate with status `SECURITY_PROBLEM` deliberately keeps its manifest (see
  `PluginScanner.kt:62`) and therefore continues to compete in `resolveGroup()` via
  `ComparableVersion` for the "best" version within the group. An attacker can therefore place a
  plugin with the same `id` as an already legitimately signed/checksummed plugin and a **higher**
  declared `version`, without having to pass the signature/checksum check itself: their own
  candidate stays unloaded because of `SECURITY_PROBLEM`, but wins the version comparison and
  thereby causes the legitimate, actually verified plugin at another location to be discarded with
  `ID_COLLISION` - a downgrade/denial-of-service vector that bypasses the checksum/signature check
  entirely, because it does not try to pass it.

## 3. Target State

* A plugin that successfully passes the existing security chain is additionally subject to a
  configurable runtime sandbox, set by a location (analogous to `securityOverride`) or globally
  (analogous to `defaultSecurityChains`).
* All runtime sandbox functions (API mediation, thread/time-limit governance, process isolation,
  violation handling) are bundled behind a single facade class **`PluginSandbox`** - analogous to
  `PluginSecurity` as the existing entry point for the security chain. `PluginManager` holds
  exactly one `PluginSandbox` instance (`val sandbox: PluginSandbox`) and calls only its methods;
  no other part of the framework addresses strategies, executors or the Java agent directly.
* The sandbox works on two levels that can be switched on independently:
  1. **API mediation inside the JVM**: direct access to risky JDK APIs (file system outside an
     allowed root directory, network, process start, reflection on host/foreign plugin classes,
     `System.exit`) is prevented via bytecode instrumentation (Java agent) or redirected to a
     restricted facade API set provided by the host.
  2. **Resource/thread governance**: threads started by a plugin run in a dedicated thread
     pool/`ThreadGroup` assigned to the plugin; a watchdog detects endless execution in lifecycle
     hooks (`onEnable`/`onDisable`/`onUnload`) and extension calls and aborts them after a
     configurable timeout or reports them to the `ExceptionHandlingStrategy`.
* For plugins classified as particularly untrusted (location type or manifest flag), a **process
  isolation** is optionally available: the plugin runs in a separate JVM subprocess and
  communicates with the host exclusively via a **TLV protocol based on ASN.1 DER**,
  encoded/decoded via **Bouncy Castle**, on plain `java.net` sockets (no RMI, no Java object
  serialization), and its process space, file system access and resources can be limited
  independently of the host process (OS means: working directory, environment variables, possibly
  memory/CPU limits of the started JVM). An OS-level user separation for the subprocess is
  deliberately **not** part of this feature (see section 9).
* The framework's persistent state (`PluginPersistenceStrategy`) is protected against subsequent
  manipulation by an HMAC integrity protection: a key generated randomly at the first start of the
  application (not part of the JAR, not hard-coded) is stored next to the persistence file; every
  stored value is signed with this key when written and verified when read. This is a
  **hardening/detection** within the existing process/user boundaries, not a guarantee against code
  running in the same process and under the same OS user (see section 9) - combined with API
  mediation, however, the key becomes unreachable for a sandboxed in-VM plugin. This protection is
  deliberately not part of `PluginSandbox`, since it applies regardless of whether a sandbox policy
  is configured for a plugin at all (see section 5).
* The security check of a candidate (checksum/signature) and its actual loading use **exactly the
  same, once-read bytes** ("byte pinning") instead of two separate file accesses - the TOCTOU gap
  from section 2 is thereby closed structurally, not merely reduced in time. The checksum digest is
  compared in constant time. The ZIP/JAR entry parser that loads the classes from memory for byte
  pinning uses the same entry resolution logic as the signature verification, so that no divergence
  between checked and loaded entry can arise for duplicate ZIP entry names. In addition,
  `SignatureSecurityStrategy` checks the validity period (`notBefore`/`notAfter`) of the certificate
  used; an expired certificate results in a failed check. A revocation check (CRL/OCSP) is
  deliberately **not** part of this hardening (see section 9).
* Sandbox violations lead to a defined, logged state instead of unhandled exceptions or silent
  persistence: the affected plugin is unloaded immediately, and the violation is reported to the
  host via a WARN log and via the `ExceptionHandlingStrategy`.
* A categorized API violation (file system/network/reflection/process start, detected via the Java
  agent) additionally marks the associated `PluginScanResult` with the standalone status
  `PluginScanStatus.POTENTIAL_ATTACK` - deliberately separate from `SECURITY_PROBLEM`, since the
  latter concerns an upfront check before loading, the former an already loaded plugin. A
  `POTENTIAL_ATTACK` plugin cannot be forced to (re)load via `PluginManager.forceLoad` and cannot
  displace a `LOADED` candidate of the same id via version spoofing (`IdCollisionResolver` treats
  it like any other non-`LOADED` status). A time-limit violation runs through the same
  reporting/unload mechanism, but triggers the same forced-unload path only after several
  consecutive timeouts for the same plugin (`PluginManager.MAX_TIMEOUT_VIOLATIONS`) - a single
  timeout alone is not considered an indication of an attack.
* The existing upfront security chain (`PluginSecurity`) remains unchanged; the sandbox is an
  orthogonal, additional protection layer that takes effect only after a successful
  `PluginLoader.load()`.
* `IdCollisionResolver` lets only candidates with status `LOADED` (i.e. those that have already
  successfully passed the security chain) compete for the "best" version within an id collision
  group. A candidate with any other status (e.g. `SECURITY_PROBLEM`) can no longer displace a
  `LOADED` candidate of the same `id` via version spoofing; its own status remains unchanged
  instead of being wrongly overwritten with `ID_COLLISION`.

## 4. Requirements

### Functional Requirements

* A host can set a sandbox configuration per `PluginLocation` (override) or globally (default),
  analogous to the existing `securityOverride`/`defaultSecurityChain` pattern.
* A host can individually allow/forbid risky API categories (file system, network, reflection,
  process start, thread creation) per sandbox configuration, or redirect them to a facade
  replacement.
* A host can set a time limit for lifecycle hooks and extension calls per sandbox configuration; an
  overrun is detected and handled.
* A host can mark a plugin as "process-isolated"; such a plugin runs in its own JVM subprocess.
* All sandbox functionality is reachable through a single class `PluginSandbox`; a host (and the
  rest of the framework) does not need to know which concrete strategy is responsible in the
  background.
* Sandbox violations are reported via the existing `ExceptionHandlingStrategy` and lead to the
  immediate unloading of the affected plugin (not of the entire host).
* A categorized API violation (file system/network/reflection/process start) marks the affected
  candidate as `PluginScanStatus.POTENTIAL_ATTACK`; a candidate marked this way cannot be forced to
  (re)load via `PluginManager.forceLoad` and cannot displace an already loaded candidate of the
  same id.
* Existing extension points and the lifecycle (`PluginLifecycle`) work unchanged for plugins
  without an activated sandbox or with an in-VM sandbox; for process-isolated plugins, the
  extension call semantics are transparently reproduced via the Bouncy Castle-based ASN.1 DER/TLV
  IPC layer, as far as technically possible.
* Every `PluginPersistenceStrategy` implementation (file-based, database, custom) can optionally be
  wrapped by an integrity protection decorator, without the framework or host having to distinguish
  between protected and unprotected keys.
* For every candidate, the security check (checksum/signature) is performed on the same bytes that
  are subsequently actually loaded - no renewed, unchecked file access between check and load.
* A signed candidate whose certificate has expired at the time of the check is recognized as a
  security problem, not silently accepted.
* A candidate with a failed security check cannot displace an already successfully checked
  candidate of the same plugin `id` via a higher declared version.

### Technical Requirements

* No dependency on `java.lang.SecurityManager`/`AccessController` (JDK 25, JEP 486: removed).
* Pure Kotlin/Gradle implementation, no new third-party dependency without asking the user (see
  `dependencies.md`); for process isolation, **Bouncy Castle** is specified by the user as the
  ASN.1 library and is thus confirmed as a third-party dependency - a custom serialization solution
  is therefore unnecessary for this part.
* `PluginSandbox` is the only public API surface of the runtime sandbox; the concrete
  `PluginSandboxStrategy` implementations (agent, thread watchdog, process isolation) are internal
  and are not addressed directly by `PluginManager` or the host - analogous to how
  `PluginSecurityStrategy` implementations are reached only via `PluginSecurity`.
* A Java agent for bytecode instrumentation requires a `-javaagent` start parameter set by the host
  (dynamic attachment was deliberately not used); this is a host-side prerequisite.
* The bytecode instrumentation uses the project's already existing Byte Buddy dependency - no new
  third-party dependency.
* Process isolation is a standalone, optional `PluginLoader` path; it must not change or slow down
  the existing in-VM loading path when it is not used.
* The persistence integrity protection uses only JDK built-ins (`javax.crypto.Mac`/`SecureRandom`),
  no new third-party dependency; it deliberately forgoes any form of OS process or user separation
  so as not to complicate the use of the plugin system.
* Byte pinning and the signature hardening use only JDK built-ins (`MessageDigest.isEqual` for the
  constant-time comparison, `ByteArray`-based class loading via `defineClass`,
  `X509Certificate.checkValidity()` for the validity check), no new third-party dependency; a
  revocation check (CRL/OCSP), which would require real PKI infrastructure, is deliberately not
  part of it.
* The sandbox configuration must be testable without needing real malicious plugins (see the
  `testing` skill before test class changes).

## 5. Architecture

* Facade class `PluginSandbox` in `org.pcsoft.framework.pluggiat.sandbox`, analogous to
  `PluginSecurity` - the central entry point for everything sandbox-related. Holds the configured
  `PluginSandboxStrategy` implementations internally and bundles:
  * `activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult` -
    activates mediation/thread governance for a freshly loaded plugin; called by `PluginManager`
    directly after `loader.load()` and before extension activation (analogous to
    `PluginSecurity.evaluate`).
  * `runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T` - runs a
    lifecycle hook or extension call under the configured thread/time-limit governance; a central
    place instead of scattered executor handling in `PluginManager`/`ExtensionAggregator`.
  * `reportViolation(pluginId: String, violation: SandboxViolation)` - uniform violation handling,
    delegates internally to `ExceptionHandlingStrategy` and `PluginPersistenceStrategy`.
  * `deactivate(pluginId: String)` - cleanup on `unload`/`reload` (executor shutdown, release of
    agent-side state for this plugin, as far as possible).
  * `PluginSandboxPolicy` (data class/configuration per location or global, analogous to
    `PluginSecurityStrategy` chains): allowed API categories, time limits, isolation level.
  * `SandboxViolation` model, modelled after `PluginSecurityCheckResult`.
* `PluginSandboxStrategy` interface with exchangeable implementations **used exclusively internally
  by `PluginSandbox`** (analogous to the `PluginSecurityStrategy` chain, which is likewise never
  addressed directly by the host, only via `PluginSecurity`): `AgentInstrumentationStrategy`,
  `ThreadWatchdog` (as a standalone collaborator), `ProcessIsolationStrategy`.
* A separate module/package `org.pcsoft.framework.pluggiat.sandbox.agent` contains the Java agent
  (`premain`/`agentmain` entry point): it registers a `ClassFileTransformer` via
  `java.lang.instrument.Instrumentation` which, when a plugin class is loaded, wraps risky JDK calls
  (`java.io.File`, `java.net.Socket`, `ProcessBuilder`, `System.exit`) in guard checks against the
  current `PluginSandboxPolicy`, and also instruments `Method.invoke`/`Class.forName` in order to
  check reflection-based circumventions at runtime as well. `PluginClassLoader` itself remains
  responsible for pure class visibility (platform → whitelist → own JARs → dependencies) and is
  complemented, not replaced, by the agent. The agent reports detected violations exclusively via
  `PluginSandbox.reportViolation`, never directly to `PluginManager`.
* A package `org.pcsoft.framework.pluggiat.sandbox.process.der` (originally created as `.ber`,
  subsequently switched to ASN.1 DER instead of BER - see below) encapsulates the use of **Bouncy
  Castle** for the IPC messages: `DerCodec` maps extension calls and return values onto Bouncy
  Castle ASN.1 types (`ASN1Integer`, `ASN1Boolean`, `DEROctetString`, `DERUTF8String`,
  `DERSequence`, `DERSet`) and sends/receives them via `ASN1OutputStream`/`ASN1InputStream`
  (explicitly with `ASN1Encoding.DER`) on the socket; the type range (`SandboxValue`) is oriented
  towards the extension call range actually needed, not towards complete ASN.1 conformance. A
  complex object (a Kotlin data class whose fields recursively consist of the same type range) is
  additionally supported as `SandboxValue.ObjectValue` - encoded as an ASN.1 `SET` of
  `SEQUENCE { fieldName UTF8String, fieldValue Value }` -, automatically mapped via
  `SandboxTypeSupport` using `kotlin-reflect`.
* A plugin configured as process-isolated continues to go through the regular in-VM loading path
  (manifest, dependencies, extension point/configuration mapping); only the instantiation of its
  extension implementation classes is omitted, and the associated subprocess is started lazily on
  the first actual extension call. `ExtensionAggregator`/`ExtensionPointRegistry` treat the
  resulting IPC proxy transparently like a normal extension implementation; the
  `ProcessIsolationStrategy` within `PluginSandbox` manages the subprocess and its lifecycle.
* `PluginManager` integrates the sandbox configuration into `PluginManagerConfiguration` analogously
  to `defaultSecurityChains` (`sandboxPolicies: Map<PluginLocationType, PluginSandboxPolicy>`,
  `PluginLocation.sandboxOverride`), holds `val sandbox: PluginSandbox` and calls
  `sandbox.activate(...)` after a successful `loader.load()` and before the activation of the
  extensions; lifecycle calls and extension method calls run via `sandbox.runGoverned(...)` instead
  of directly in the host's calling thread.
* Package `org.pcsoft.framework.pluggiat.persistence.integrity` with
  `IntegrityProtectedPersistenceStrategy`: a generic decorator around any
  `PluginPersistenceStrategy`, used **independently of `PluginSandbox`** (it protects even when no
  sandbox policy is configured for a plugin at all). On first access, a 256-bit key is generated
  via `SecureRandom` and stored in a separate key file next to the underlying persistence source
  (for `FilePersistenceStrategy`, e.g. `<filename>.key` in the same directory; for non-file-based
  strategies via an explicitly configured path), if none exists yet - otherwise the existing key is
  read. Each `write(pluginId, key, value)` call additionally computes an HMAC (`javax.crypto.Mac`,
  e.g. `HmacSHA256`) over `(pluginId, key, value)` and stores it under a derived additional key in
  the same underlying `PluginPersistenceStrategy`; `read(pluginId, key)` verifies the stored HMAC
  against the read value and, on mismatch, returns `null` (fail-safe: treated as "not set") instead
  of the manipulated value, including a log message.
* Type `PinnedPluginContent` in `org.pcsoft.framework.pluggiat.scanner`: encapsulates the raw bytes
  of a candidate read once - for `SingleJarScanStrategy`/`ZipJarScanStrategy` a single `ByteArray`,
  for `MultiJarWithOwnFolderScanStrategy` a `Map<String, ByteArray>` ordered by file name. The bytes
  are read **once**, directly as part of `PluginScanner.applySecurityCheck`;
  `PluginSecurityStrategy.check()` has an overload that checks `PinnedPluginContent` instead of a
  `Path` (`ChecksumSecurityStrategy`/`SignatureSecurityStrategy` adapted accordingly).
  `PluginScanResult` carries the `PinnedPluginContent` of a successfully checked candidate on until
  loading.
* A shared helper module `org.pcsoft.framework.pluggiat.classloader.jar` provides a single function
  for resolving ZIP/JAR entries from `PinnedPluginContent` (`resolveJarEntries(content): Map<String,
  ByteArray>`, deterministic for duplicate entry names, "last entry wins" like the JDK ZIP
  behaviour). Both `SignatureSecurityStrategy` (verification, for finding the manifest JAR/checksum
  list) and `PinnedPluginClassLoader` (loading) use this one resolution function, instead of each
  operating their own ZIP parser instances with potentially deviating interpretation. This closes
  the duplicate-entry divergence described in section 2 structurally, not merely by convention.
* In addition to the existing key check, `SignatureSecurityStrategy` calls
  `certificate.checkValidity()` on every certificate used; a
  `CertificateExpiredException`/`CertificateNotYetValidException` is treated as
  `PluginSecurityCheckResult.Failure`. No CRL/OCSP integration, no
  `CertPathValidator`/PKIX trust anchor setup - merely a check of the validity period of the
  certificate already pinned via `PublicKeyProviderStrategy`.
* `PluginLoader.load()` has an overload that accepts a `PinnedPluginContent` instead of a `Path`; it
  no longer builds a `URLClassLoader`, but a memory-based `PinnedPluginClassLoader` that resolves
  classes/resources exclusively from the pinned bytes (JAR entries are indexed once via the shared
  `resolveJarEntries` function, `findClass` uses `defineClass(name, bytes, off, len)`). The pinned
  bytes are held in `LoadedPlugin` for the entire lifetime of the loaded plugin, since classes may
  be loaded lazily. The old, `Path`-based loading path remains for callers that do not need pinning
  (e.g. a host-side `InsecureSecurityStrategy` use case without an integrity claim), but is no
  longer used internally by `PluginManager` for freshly checked candidates.

**Subsequent deviation (after feature completion)**: The IPC wire format was switched from ASN.1 BER
to ASN.1 DER (`ASN1OutputStream.create(output, ASN1Encoding.DER)` instead of the BER default) and
the package `sandbox.process.ber`/the class `BerCodec` was renamed to
`sandbox.process.der`/`DerCodec`. In addition, the IPC has since supported complex objects (Kotlin
data classes, recursively from the existing type range) as `SandboxValue.ObjectValue`,
automatically mapped via `kotlin-reflect` (new `implementation` dependency, confirmed by the user).
This does not narrow the "closed type range" described in section 6, but extends it by one further,
equally closed case.

**Subsequent deviation (after feature completion, folder layout, process isolation and lock)**:
The folder layout has been removed: `PinnedPluginContent` now knows only the variant `Single` (no
`Map` ordered by file name), `SignatureSecurityStrategy` has neither the discovery of a checksum
list (`META-INF/plugin-checksums.txt`) nor the parameter `checksumAlgorithm`, and
`PluginResourceLimits.MAX_CANDIDATE_TOTAL_SIZE_BYTES` was removed. Process isolation hands the
pinned bytes (JAR or ZIP with JARs) to the subprocess at start via its stdin pipe (8-byte length
and bytes, at most 256 MiB); the subprocess builds a memory-based `PinnedPluginClassLoader` from
them. Neither a temp directory nor JAR files are created on disk, so ZIP candidates are now
supported in a process-isolated way (previously rejected); the start arguments are `pluginId`, IPC
token and allowed categories. A plugin marked `POTENTIAL_ATTACK` is refused not only by
`forceLoad`, but also by `reload` and `reactivate` (`IllegalStateException`). The initial situation
described in section 2 (loading via `URLClassLoader`/`Files.newDirectoryStream`) is historical and
remains unchanged.

## 6. Risks and Open Questions

* **No `SecurityManager` available** (JDK 25 / JEP 486): every in-VM enforcement is based on
  bytecode instrumentation and convention, not on a permission model guaranteed by the JDK.
* **Java agent requires host cooperation** (JDK 21+/JEP 451): without the `-javaagent` start
  parameter, `pluggiat` cannot activate the agent on its own - this is a host integration
  requirement; a host without the parameter set receives an exception at start.
* **Residual gap despite the agent**: bytecode that references a risky JDK class before the agent is
  registered (very early class initialization) cannot be captured retroactively. Only code loaded
  via `PluginClassLoader` is instrumented - a host method released via the SDK whitelist that
  performs a risky operation on behalf of the plugin remains unmediated.
* **No hard thread abort without a process boundary**: `Thread.stop()` is unsafe; the thread
  watchdog can detect misbehaviour (`callTimeout`, best-effort `Future.cancel(true)`), but only
  process isolation can terminate a hanging call with certainty.
* **Deliberately no OS process/user separation**: The user decided to deliberately forgo a hard,
  operating-system-enforced separation (e.g. Windows restricted tokens/integrity levels as an
  equivalent to Unix user switching) in order not to introduce additional platform dependencies
  (JNA/JNI for Windows) and operational complexity. Consequence: neither process isolation nor the
  persistence integrity protection offers a hard guarantee against code running in the same
  process/under the same OS user as the host - both are hardening/detection, not a guarantee. This
  is explicitly named as such in the MkDocs security chapter.
* **Memory consumption and functional scope of byte pinning**: pinned raw bytes remain in memory for
  the entire plugin lifetime; native libraries and file-path-dependent resource accesses from
  plugin code may work differently than before with a purely memory-based class loader - documented
  as a known limitation in `sandbox.md`/`.de.md`.
* **Deliberately no revocation check (CRL/OCSP)**: A real revocation check would require an
  operated PKI infrastructure, which typically does not exist for self-signed certificates pinned
  via `PublicKeyProviderStrategy`. Only the validity period check (`notBefore`/`notAfter`) is added.
  A compromised but still valid key remains trusted until its regular expiry - a deliberate limit
  accepted by the user.
* **Performance overhead**: bytecode instrumentation, dedicated executors, Bouncy Castle ASN.1 DER
  IPC, HMAC computation per persistence access and complete reading of the candidate bytes before
  loading add latency/memory requirements; a host with performance-critical extension calls can
  switch the sandbox off selectively.
* **Backward compatibility**: default behaviour without a configured sandbox policy corresponds to
  the state without a sandbox (no restriction), analogous to the explicit nature of
  `InsecureSecurityStrategy` - a sandbox does not become active implicitly. The persistence
  integrity protection is likewise a decorator that a host must use explicitly. An already accepted,
  meanwhile expired signature certificate leads to a new failure where there was none before - an
  intended behaviour change compared with the initial state described in section 2.

## 7. Feature Completion Criteria

* A host can configure a sandbox policy for every `PluginLocation` (or globally) that has no effect
  without explicit configuration (no implicit security gain without a host decision, analogous to
  `InsecureSecurityStrategy`).
* All runtime sandbox functionality is reachable exclusively via `PluginSandbox`; `PluginManager`
  and the host do not address any concrete strategy directly.
* An in-VM plugin with activated, agent-based API mediation demonstrably can no longer access at
  least the file system, network and process start without restriction - neither directly nor via
  reflection - provided the policy forbids it.
* An in-VM plugin whose lifecycle hook exceeds the configured time limit does not block the host
  process permanently and is recognized as a sandbox violation.
* A plugin configured as process-isolated demonstrably runs in its own process whose crash does not
  terminate the host process, whose communication runs exclusively via the Bouncy Castle-based ASN.1
  DER TLV protocol (values including complex objects as ASN.1 `SET`/`SEQUENCE`), and whose
  extensions remain usable via the normal `getExtensions`/`getFirstExtension` API.
* A direct manipulation of the persistence file without knowledge of the associated HMAC key is
  demonstrably detected on the next `read` and treated as "not set", not silently adopted.
* A candidate whose file(s) are swapped between security check and loading is demonstrably loaded
  with the originally checked content; the checksum comparison is constant-time; a JAR with
  duplicate ZIP entry names is interpreted identically by verification and loading; a signed
  candidate with an expired certificate is rejected.
* A candidate with a failed security check demonstrably can no longer displace an already
  successfully loaded candidate of the same plugin `id` from the collision resolution via a higher
  declared version.
* Sandbox violations (categorized API violation as well as repeated time-limit overruns) are
  traceable via the same observation/persistence layer as existing security and deactivation
  reasons.
* The documented structural limits (no `SecurityManager`, host prerequisite for the Java agent, no
  hard in-VM thread abort, Bouncy Castle dependency for process isolation, deliberate forgoing of OS
  process/user separation, memory/functional-scope tradeoffs of byte pinning, and deliberate
  forgoing of a revocation check) are recorded in the MkDocs security chapter as a deliberate
  design decision, not concealed.
