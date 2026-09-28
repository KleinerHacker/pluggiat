# Changelog

All notable, user-visible changes to pluggiat are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `IntegrityProtectedPersistenceStrategy`: an optional decorator for any `PluginPersistenceStrategy`
  that HMAC-protects every stored value, so a value tampered with directly in the underlying storage
  is detected and treated as unset.
- `PluginSandbox`: a new host-wide facade for a plugin's runtime sandbox, configurable per
  `PluginLocation` (`sandboxOverride`) or globally per location type (`defaultSandboxPolicy`). Bytecode
  API mediation is enforced via a Java agent (requires the host JVM to be started with this module's
  own JAR as a `-javaagent`): a plugin whose policy restricts a `SandboxApiCategory` is blocked from
  that access, covering file, network, reflection/invoke, native/process, and thread/executor APIs.
  Blocking a call unloads the plugin, marks it `PluginScanStatus.POTENTIAL_ATTACK` and reports it to
  the host's `ExceptionHandlingStrategy`. `PluginSandboxPolicy.callTimeout` bounds how long a lifecycle
  hook or extension call may run, throwing `SandboxTimeoutException` instead of blocking the host
  thread forever; three accumulated timeouts of a plugin escalate to the same forced
  unload/`POTENTIAL_ATTACK` handling. `PluginSandboxPolicy.isolationLevel = SandboxIsolationLevel.PROCESS`
  runs a plugin's extensions in a separate JVM subprocess, proxied over a loopback socket, so a
  subprocess crash or hang can never take down the host process; only a minimal, closed set of
  parameter/return types is transportable this way, a method outside that set throws
  `UnsupportedSandboxTypeException`. A single-JAR or ZIP plugin is handed to the subprocess as its
  security-checked bytes through the subprocess's standard input and loaded from memory, so no
  temporary files are created.
- Every security- and sandbox-relevant decision is now logged at TRACE level, naming the concrete
  strategy and the decision made, so a host can enable TRACE logging to follow why a plugin was
  accepted, rejected or blocked.

### Security

- `SignatureSecurityStrategy` now rejects an expired or not-yet-valid signing certificate, and a
  signer presenting no X.509 certificate.
- Plugin id collision resolution no longer lets a candidate that failed its security chain displace an
  already verified candidate of the same id by declaring a higher manifest version.
- A plugin can no longer supply its own copy of a pluggiat class or resource: everything under
  `org.pcsoft.framework.pluggiat` is always resolved from the host first, closing a way for a plugin to
  ship its own `SandboxGuardRegistry` and defeat sandbox checks.
- Deactivating a plugin now *revokes* its sandbox registration instead of removing it, so a plugin
  thread surviving its plugin's unload has every guarded call blocked from then on.
- A plugin marked `PluginScanStatus.POTENTIAL_ATTACK` can no longer be brought back: `reload`,
  `reactivate` and `forceLoad` all refuse it with an `IllegalStateException`. Previously `reload` could
  load it again after a successful security re-check.
- The sandbox now also mediates `URLClassLoader`, `ServiceLoader`, `ModuleLayer`, `ForkJoinTask`,
  parallel streams, and the JDK's built-in `com.sun.net.httpserver` HTTP server.
- A process-isolated plugin is now sandboxed inside its own subprocess as well, using the same guarded
  rules as the host; the process-isolation IPC is now authenticated with a per-subprocess token and
  bounded message size, so another local process can no longer drive a plugin's extension methods
  through it.
- `OpenPgpKeyserverPublicKeyProviderStrategy` now binds a keyserver's answer to the requested key id
  instead of accepting any key in the response, and requires `https://` for its base URL.
- Reading a plugin candidate is now size- and depth-bounded, so an oversized or deeply nested candidate
  is reported as `PluginScanStatus.SECURITY_PROBLEM` instead of exhausting host JVM memory.
- Persisted plugin state (and the integrity key file) is now written atomically and readable by its
  owner only.
- A plugin manifest's `id` (and each `dependencies[].id`) is now restricted to letters, digits, `.`,
  `_` and `-`, and to 128 characters; among duplicate manifest entries in one JAR, the last one now
  consistently wins.

### Changed

- **Breaking for existing persisted state.** The HMAC of `IntegrityProtectedPersistenceStrategy` is now
  computed over length-prefixed fields and compared in constant time; values stored by an earlier
  version read back as unset and have to be written again.
- **Breaking for existing persisted state.** `FilePersistenceStrategy` now separates plugin id and key
  with `|` instead of `.` in the `PROPERTIES`/`XML` formats; entries written by an earlier version are
  ignored with a warning. `JSON`/`YAML` are unaffected.
- `PluginSecurity.reevaluate`/`reevaluateAndPin` no longer throw `NoSuchElementException` when a
  candidate has disappeared since it was first scanned - that is now an ordinary failed re-check.
- The manifest's `icon` format and `legal.license` are now actually checked when a manifest is read:
  an unrecognised icon format or a license that is not a known SPDX license expression (`AND`, `OR`,
  `WITH` and `+` are understood) is logged as a warning. This never invalidates a manifest, and the
  check is skipped, with a warning, where the JVM cannot perform it.
- `PluginManager` now ends the lifecycle of the previous extension instances (`onDisable`, then
  `onUnload`) before every `scan`, `reload`, `unload` and `forceLoad` re-creates the extensions of all
  loaded plugins. A plugin unloaded after a sandbox violation still runs no further hooks.

### Fixed

- A proxied extension method declared to return a `Set` (or a `Map` subtype other than a plain
  `Map`) no longer fails with a `ClassCastException`: a `Set` result stays a `Set`, and a collection
  or map type that cannot be rebuilt around proxied elements is passed through unchanged with a
  warning.

### Removed

- **Breaking:** the folder plugin layout is gone. `MultiJarWithOwnFolderScanStrategy` and the checksum
  list (`META-INF/plugin-checksums.txt`) it relied on were removed; a plugin is a single JAR or a ZIP
  holding its JARs (`SingleJarScanStrategy`, `ZipJarScanStrategy`).
- **Breaking:** `SignatureSecurityStrategy` no longer has a `checksumAlgorithm` constructor parameter,
  and `PluginResourceLimits.MAX_CANDIDATE_TOTAL_SIZE_BYTES` was removed; both only served the folder
  layout.

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
