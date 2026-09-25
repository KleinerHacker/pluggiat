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

### Security

- `SignatureSecurityStrategy` now rejects a candidate whose signing certificate has expired or is
  not yet valid, instead of accepting it exactly like a currently valid certificate as long as the
  public key matched.

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
