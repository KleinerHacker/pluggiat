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
