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
