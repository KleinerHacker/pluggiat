# Changelog

All notable, user-visible changes to pluggiat are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Plugin manifest format (`META-INF/plugin.yml`/`.yaml`) validated against a JSON schema, including
  automatic icon format detection and optional SPDX license matching.
- Extension point mechanism: hosts define extension points via `@ExtensionPoint`-annotated
  configuration classes and register them with an `ExtensionPointRegistry`; manifest
  `extensions.<key>[]` entries are then resolved and instantiated via an `ExtensionAggregator`,
  including conflict handling for exclusive extension points.
- Plugin scanner: hosts configure one or more `PluginLocation`s and scan them via `PluginScanner`,
  using `SingleJarScanStrategy`, `MultiJarWithOwnFolderScanStrategy` or `ZipJarScanStrategy`
  (default) to discover valid and invalid plugin candidates. `ZipJarScanStrategy` reads a location's
  `.zip` files directly via a mounted NIO ZIP filesystem instead of unpacking them onto disk.
- Security concept: each `PluginLocation` is protected by an ordered, freely extensible fallback
  chain of `PluginSecurityStrategy` implementations (`InsecureSecurityStrategy`,
  `SignatureSecurityStrategy` with a pluggable `PublicKeyProviderStrategy`,
  `ChecksumSecurityStrategy`); a candidate failing every strategy of its chain is reported as
  `PluginScanStatus.SECURITY_PROBLEM`. No implicit default chain - hosts must configure one
  explicitly per location type or per location. Checksums (for `ChecksumSecurityStrategy` and
  `SignatureSecurityStrategy`'s `MultiJarWithOwnFolderScanStrategy` checksum list) are computed via
  a pluggable `ChecksumAlgorithm`, shipped as `MessageDigestChecksumAlgorithm` wrapping any JCA
  `MessageDigest` algorithm name (e.g. `"MD5"`, `"SHA-256"`, `"SHA-512"`); SHA-512 is the default.
- Isolated plugin class loading: `PluginLoader` creates a parent-last `PluginClassLoader` per
  plugin, exposing only a host-configured SDK whitelist (`SdkWhitelistEntry`, package-prefix based,
  optionally non-recursive) plus JDK platform classes; all other host-internal code stays
  unreachable, including via reflection.
- Plugin dependency graph: `required`/`optional` manifest dependencies are resolved into a load
  order via topological sorting, with cycle detection; a missing `required` dependency invalidates
  a plugin, a missing `optional` one is simply skipped. Cross-location visibility is governed by a
  `PluginDependencyStrategy` (global default, optional per-location override):
  `UnrestrictedPluginDependencyStrategy` (default, all locations visible),
  `LocationPluginDependencyStrategy` (explicit allow-list) and `DisallowPluginDependencyStrategy`
  (no plugin dependencies at all, even within the same location).
- `PluginLoader.load` performs no security check of its own and is unconditionally callable, so a
  host application can knowingly load a plugin despite a failed security check; deciding whether
  that is warranted, and logging it, is entirely up to the host application.
- `PluginLifecycle` interface (`onLoad`/`onEnable`/`onDisable`/`onUnload`) any extension
  implementation class may optionally implement; hooks run in call order `onLoad` before `onEnable`
  and `onDisable` before `onUnload`, with `onUnload` always followed by the plugin's class loader
  being discarded.
- Generic `PluginPersistenceStrategy` key-value persistence, backing both the checksum security
  strategy and the new enabled/disabled status: `NoPersistenceStrategy` (default),
  `CustomPersistenceStrategy`, `FilePersistenceStrategy` (properties/JSON/YAML/XML),
  `DatabasePersistenceStrategy` (plain JDBC) and `ObjectPersistenceStrategy` (delegates to a pair
  of host-provided getter/setter functions).
- Persistent plugin enabled/disabled status, checked before any extension class of a disabled
  plugin is resolved; a disabled plugin's classes are never loaded, not even transitively.
- Runtime error isolation: every extension call is routed through a runtime enforcement proxy
  (`java.lang.reflect.Proxy` for an interface extension point API type, a ByteBuddy subclass for an
  open class) resolving escaping exceptions via a configurable `ExceptionHandlingStrategy`
  (`IGNORE`/`UNLOAD`/`CRASH`, with a class-hierarchy-aware, chainable `DefaultExceptionHandlingStrategy`);
  only `PluginExecutionException`/`PluginFatalException` ever reach the host. Proxy-eligible return
  values (interfaces/open classes), including array elements, `Collection` elements and `Map`
  values, are wrapped recursively the same way.
- `PluginManager` central, stateful entry point with a `pluginManager { ... }` Kotlin builder DSL,
  bundling plugin locations, default security chains, dependency strategy, persistence strategy,
  exception handling strategy, SDK whitelist, host version and extension points (`extensionPoint(...)`);
  exposes pre-wired `scanner`/`security`/`loader`/`registry` instances.
- `PluginManager.scan()` orchestrates the full scan-load-enable flow across all configured locations
  in one call: security check, cross-location plugin ID collision resolution (version-based; on a
  tie the whole group is rejected, no further tie-breaking), `minVersion` compatibility check against
  `hostVersion`, dependency-ordered class loading and extension activation, exposed via the
  `scanResults`/`loadedPlugins`/`extensionsByKey` properties. Two new `PluginScanStatus` values,
  `ID_COLLISION` and `MIN_VERSION_VIOLATION`, plus `LOAD_FAILED` for a candidate that failed
  `PluginLoader.load` after passing every earlier check.
- `PluginManager.reload(pluginId)`/`unload(pluginId)` as the regular, secure (re-)activation and
  deliberate host-initiated deactivation of a single loaded plugin; `unload` invokes
  `onDisable`/`onUnload` on the plugin's real extension instances, persists it disabled and closes
  its class loader.
- `PluginManager.forceLoad(pluginId)` loads a plugin candidate unconditionally, bypassing its current
  `scanResults` status; a `persistException` flag additionally records a permanent, generic security
  exception for the plugin id, skipping every future security check for it. A new
  `PersistableSecurityStrategy` interface lets a strategy persist its own accepted state instead
  (implemented by `ChecksumSecurityStrategy`), invoked via `PluginManager.write<T>(pluginId)`.
- `PluginManager.getExtensions<T>(key)`/`getFirstExtension<T>(key)` for typed access to currently
  active extension implementations.
- `PluginScanResult` now keeps its `manifest` for every status except `MANIFEST_NOT_FOUND`/
  `MANIFEST_INVALID` (previously cleared on `SECURITY_PROBLEM` too), so a host can still inspect and
  force-load a candidate that failed security.

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
