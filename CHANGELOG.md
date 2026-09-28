# Changelog

All notable, user-visible changes to pluggiat are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `IntegrityProtectedPersistenceStrategy`: an optional decorator for any `PluginPersistenceStrategy`
  that HMAC-protects every stored value with a `SecureRandom`-generated key, so a value tampered
  with directly in the underlying storage is detected and treated as unset instead of being
  returned as if it were legitimately written.
- `PluginSandbox`: a new host-wide facade for a plugin's runtime sandbox, configurable per
  `PluginLocation` (`sandboxOverride`) or globally per location type (`defaultSandboxPolicy`).
  Bytecode API mediation is now enforced via a Java agent: a plugin whose policy restricts a
  `SandboxApiCategory` is blocked from that access. Coverage spans both the classic `java.io`/`java.net`
  APIs and their `java.nio` equivalents (`Files`, `Path`, file and network channels, the
  `FileSystemProvider`/`SelectorProvider` SPIs beneath them), the `javax.net` socket factories, DNS
  resolution, `java.rmi`/JNDI, `Runtime.exec` and native library loading, the whole `java.lang.reflect`
  and `java.lang.invoke` packages plus `Unsafe` and deserialization, and thread/executor creation -
  reached directly, through a subclass, through reflection or through a method reference. Blocking a
  call immediately unloads the plugin, marks it `PluginScanStatus.POTENTIAL_ATTACK` (never eligible for
  `forceLoad` again) and reports it to the host's `ExceptionHandlingStrategy`. Requires the host JVM to
  be started with this module's own JAR as a `-javaagent`; a restrictive policy activated without it
  aborts startup with `SandboxAgentNotActiveException`. `PluginSandboxPolicy.callTimeout` now bounds
  how long a lifecycle hook or extension call may run, including on a recursively returned nested
  value (e.g. a factory method's result): a call exceeding it throws `SandboxTimeoutException`
  instead of blocking the host thread forever, and its now-abandoned worker thread is shut down and
  replaced so a later call for the same plugin is not stuck behind it - `PluginManager.unload()`
  itself always completes fully even if a lifecycle hook times out. Three consecutive timeouts for
  the same plugin escalate to the same forced unload/`POTENTIAL_ATTACK` handling as a category-
  attributed violation (persisted reason `SANDBOX_TIMEOUT_LIMIT`), so a persistently hanging plugin
  cannot accumulate an unbounded number of abandoned threads. A `PluginSandboxPolicy` with
  `isolationLevel = SandboxIsolationLevel.PROCESS` now runs a plugin's extensions in a separate JVM
  subprocess instead of the host's own JVM: every extension call is transparently proxied across the
  process boundary as ASN.1 DER over a loopback socket, so a subprocess crash or hang can never take
  down the host process. The subprocess is started lazily on first use, torn down on unload/reload,
  and an unexpected exit is reported as a sandbox violation like an IP-03 timeout. Only a minimal,
  closed set of parameter/return types is transportable this way (`Int`/`Long`/`Boolean`/`ByteArray`/
  `String`/`Unit`, `List` thereof, and a complex object - a Kotlin data class whose fields are
  recursively one of the former); a method outside that set throws
  `UnsupportedSandboxTypeException` immediately at the call site, without ever contacting the
  subprocess - a permanent limitation, not a temporary gap. Process isolation still runs the
  subprocess under the same OS user as the host, with no additional OS-level privilege separation.
- Every security- and sandbox-relevant decision (signature/checksum verification, public key
  resolution, sandbox guard checks, plugin dependency visibility, exception-handling resolution) is
  now logged at TRACE level, naming the concrete strategy involved and the decision it made - so a
  host can enable TRACE logging to fully follow why a plugin was accepted, rejected or blocked.

### Security

- `SignatureSecurityStrategy` now rejects a candidate whose signing certificate has expired or is
  not yet valid, instead of accepting it exactly like a currently valid certificate as long as the
  public key matched.
- Plugin id collision resolution no longer lets a candidate that failed its security chain displace
  an already successfully verified candidate of the same id by declaring a higher manifest version;
  only candidates that passed their security chain now compete on version.
- A plugin can no longer supply its own copy of a pluggiat class or resource: everything under
  `org.pcsoft.framework.pluggiat` is now always resolved from the host, before the SDK whitelist and
  before the plugin's own JARs, and a pinned candidate can no longer serve a resource below that
  prefix either. Previously a plugin shipping its own `SandboxGuardRegistry` had that copy loaded
  (the class loader is parent-last), which let its sandbox guard calls resolve into a registry it
  controlled - and therefore allowed everything. A class that exists but cannot be linked now fails
  the load loudly instead of being silently skipped in favour of a less trusted copy of the same name.
- Deactivating a plugin now *revokes* its sandbox registration instead of removing it: a plugin
  thread that survives its plugin's unload has every guarded call blocked from then on. Previously
  such a thread found no policy for its class loader and was granted every guarded API - the state an
  attacking plugin would aim for. A sandbox violation reported for an already-unloaded plugin is now
  handled (marked, persisted, reported) instead of being dropped.
- The sandbox now also mediates `URLClassLoader`, `ServiceLoader` and `ModuleLayer` (as `REFLECTION`),
  `ForkJoinTask` as well as `Stream.parallel()`/`Collection.parallelStream()` (as `THREAD_CREATION`),
  and the JDK's built-in `com.sun.net.httpserver` HTTP server (as `NETWORK`). A plugin-defined
  subclass of `URLClassLoader` inherits the whole-type guard rather than `ClassLoader`'s narrower
  member-level rules.
- A process-isolated plugin is now sandboxed inside its own subprocess as well: the subprocess is
  started with pluggiat's own JAR as its `-javaagent`, loads the plugin through a `PluginClassLoader`
  and registers the plugin's effective policy, so a guarded call is blocked there by the same rules as
  in the host. Previously the subprocess ran the plugin entirely unmediated, making process isolation a
  *weaker* sandbox than in-VM mediation. Activating a restricted process-isolated policy now fails with
  `SandboxAgentNotActiveException` when no agent JAR is available to hand to the subprocess. The
  subprocess is additionally started from the candidate's security-checked bytes instead of re-reading
  its path.
- The process-isolation IPC is now authenticated: every call carries a per-subprocess token generated
  with `SecureRandom` and compared in constant time, so another local process that finds the
  subprocess's loopback port can no longer drive a plugin's extension methods through it. Accepted
  connections have a read timeout, and one decoded message is bounded to 16 MiB so a forged length
  header cannot exhaust memory.
- `OpenPgpKeyserverPublicKeyProviderStrategy` now binds a keyserver's answer to the key id that was
  requested: it searches every key ring of the response and rejects it - resolving to `null` - unless a
  key's fingerprint or 64-bit key id matches. Previously the first key of the first ring was accepted,
  so a keyserver could hand out any key for any plugin. The base URL must now use `https://` (a
  loopback host may still use plain HTTP), otherwise construction fails with
  `IllegalArgumentException`.
- Reading a plugin candidate is now bounded (candidate file and total candidate size, unpacked size per
  entry and per candidate, archive nesting depth, manifest size). A candidate exceeding a bound is
  reported as `PluginScanStatus.SECURITY_PROBLEM` instead of taking the host JVM down with an
  `OutOfMemoryError` while being read - which previously needed neither a signature nor a successful
  load.
- `SignatureSecurityStrategy` now rejects a signer that presents no X.509 certificate (its validity
  could not be checked at all), and only exempts the signature files the JAR verifier actually consumes
  from the signing requirement. An entry merely *named* like signing metadata (`META-INF/<name>.SF`
  without a matching signature block) now has to be signed like any other, closing a way to ship
  unsigned content inside a signed JAR.
- Persisted plugin state is now written atomically and readable by its owner only, as is the integrity
  key file; `DatabasePersistenceStrategy` writes each value in one transaction. `PluginManager.write`
  and `ChecksumSecurityStrategy.persist` now derive the accepted state from the candidate's
  security-checked bytes rather than re-reading its path.
- A plugin manifest's `id` (and each `dependencies[].id`) is now restricted to letters, digits, `.`,
  `_` and `-`, starting and ending with a letter or digit, and to 128 characters. Among duplicate
  manifest entries in one JAR, the *last* one now wins - the same rule the signature check and the
  class loader already applied, so a candidate can no longer present one manifest for inspection and
  another for loading.

### Changed

- **Breaking for existing persisted state.** The HMAC of `IntegrityProtectedPersistenceStrategy` is now
  computed over length-prefixed fields (so a stored MAC cannot be moved to another plugin id or key)
  and compared in constant time. Values stored by an earlier version therefore read back as unset and
  have to be written again - for pluggiat's own state that means a plugin's enabled flag or accepted
  checksum is re-confirmed once.
- **Breaking for existing persisted state.** `FilePersistenceStrategy` now separates plugin id and key
  with `|` instead of `.` in the `PROPERTIES`/`XML` formats, because `.` is legal inside a plugin id and
  made the flattening ambiguous. Entries written by an earlier version are ignored with a warning; the
  `JSON`/`YAML` formats are unaffected.
- **Breaking for existing accepted checksums of folder candidates.** `ChecksumSecurityStrategy` now
  length-prefixes each file of a multi-JAR candidate before digesting, so bytes can no longer be moved
  across file boundaries without changing the checksum. Such a candidate's accepted checksum has to be
  accepted once more; single-file candidates are unaffected.
- `PluginSecurity.reevaluate`/`reevaluateAndPin` no longer throw `NoSuchElementException` when a
  candidate has disappeared since it was first scanned - that is now an ordinary failed re-check, and
  the candidate is read only once instead of twice.

## [0.1.0]

### Added

- Plugin manifest format (`META-INF/plugin.yml`/`.yaml`) validated against a JSON schema, including
  automatic icon format detection and optional SPDX license matching.
- Extension point mechanism: hosts define extension points and register them with an
  `ExtensionPointRegistry`; manifest `extensions.<key>[]` entries are resolved and instantiated,
  including conflict handling for exclusive extension points.
- Plugin scanner: hosts configure one or more `PluginLocation`s and scan them via `PluginScanner`
  to discover valid and invalid plugin candidates, supporting single-JAR, multi-JAR-with-own-folder
  and ZIP-packaged plugin layouts (ZIPs are read directly, without unpacking to disk).
- Security concept: each `PluginLocation` is protected by an ordered, freely extensible fallback
  chain of security strategies (insecure/no check, signature-based with a pluggable public-key
  provider, or checksum-based); a candidate failing every strategy of its chain is reported as a
  security problem. No implicit default chain - hosts must configure one explicitly. Checksums are
  computed via a pluggable algorithm (any JCA `MessageDigest`, e.g. MD5/SHA-256/SHA-512), with
  SHA-512 as the default.
- Isolated plugin class loading: each plugin runs in its own class loader, exposing only a
  host-configured SDK whitelist plus JDK platform classes; all other host-internal code stays
  unreachable, including via reflection.
- Plugin dependency graph: `required`/`optional` manifest dependencies determine load order, with
  cycle detection; a missing `required` dependency invalidates a plugin, a missing `optional` one is
  simply skipped. Cross-location visibility of dependencies is configurable (unrestricted, an
  explicit allow-list, or disallowed entirely).
- A host application can knowingly load a plugin despite a failed security check; deciding whether
  that is warranted, and logging it, is entirely up to the host application.
- `PluginLifecycle` interface (`onLoad`/`onEnable`/`onDisable`/`onUnload`) any extension
  implementation class may optionally implement; hooks run in call order `onLoad` before `onEnable`
  and `onDisable` before `onUnload`, with `onUnload` always followed by the plugin's class loader
  being discarded.
- Generic key-value persistence for plugin state, backing both the checksum security strategy and
  the new enabled/disabled status; hosts can plug in properties/JSON/YAML/XML files, a JDBC
  database, a custom strategy, or their own storage via getter/setter functions.
- Persistent plugin enabled/disabled status, checked before any extension class of a disabled
  plugin is resolved; a disabled plugin's classes are never loaded, not even transitively.
- Runtime error isolation: every extension call is routed through a runtime enforcement proxy
  resolving escaping exceptions via a configurable strategy (ignore/unload/crash the plugin); only
  well-defined plugin exceptions ever reach the host. Nested proxy-eligible return values (in
  arrays, collections and maps) are wrapped recursively the same way.
- `PluginManager` central, stateful entry point with a `pluginManager { ... }` Kotlin builder DSL,
  bundling plugin locations, default security chains, dependency strategy, persistence strategy,
  exception handling strategy, SDK whitelist, host version and extension points.
- `PluginManager.scan()` orchestrates the full scan-load-enable flow across all configured locations
  in one call: security check, cross-location plugin ID collision resolution (version-based; on a
  tie the whole group is rejected), minimum-host-version compatibility check, dependency-ordered
  class loading and extension activation.
- `PluginManager.reload(pluginId)`/`unload(pluginId)` as the regular, secure (re-)activation and
  deliberate host-initiated deactivation of a single loaded plugin.
- `PluginManager.forceLoad(pluginId)` loads a plugin candidate unconditionally, bypassing its
  current scan status; it can additionally record a permanent security exception for the plugin id,
  skipping every future security check for it.
- `PluginManager.getExtensions<T>(key)`/`getFirstExtension<T>(key)` for typed access to currently
  active extension implementations.
- A host can still inspect and force-load a plugin candidate that failed its security check.
- Three shipped public-key providers for signature-based security: from a Java `KeyStore`, a
  directly supplied fixed key, or a key resolved from an HKP-compatible OpenPGP keyserver (e.g.
  `keys.openpgp.org`), with configurable timeout and result caching.

### Changed

- **Breaking:** `PluginSecurityChainEvaluator` was renamed to `PluginSecurity` and is now a freely
  usable public API, analogous to `PluginLoader`.
- **Breaking:** `ChecksumSecurityStrategy` now takes a `PluginPersistenceStrategy` (key
  `"checksum"`) instead of a separate `ExpectedChecksumCallback`.
- **Breaking:** the `pluginManager { ... }` builder DSL's `location(...)`, `defaultSecurityChain(...)`
  and `sdkWhitelistEntry(...)` now take a nested builder block (`location { path = ...; type = ... }`,
  `defaultSecurityChain { type = ...; addStrategy(...) }`, `securityOverride { addStrategy(...) }`)
  instead of a pre-built value/list argument.
- **Breaking:** `ExtensionAggregator`'s `classResolver: ExtensionClassResolver` constructor parameter
  was replaced by `classResolverFor: (pluginId: String) -> ExtensionClassResolver`, so each plugin can
  be resolved through its own isolated class loader.

### Removed

- **Breaking:** `ChecksumPersistenceCallback` was removed; an accepted checksum is now recorded via
  `PluginPersistenceStrategy.write(pluginId, "checksum", checksum)` on the same instance
  `ChecksumSecurityStrategy` reads from.
