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
