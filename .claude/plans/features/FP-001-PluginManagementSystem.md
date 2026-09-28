# Feature Plan: Plugin Management System (COMPLETED)

## 1. Objective

* Dynamic plugin system for JVM applications
* JAR/ZIP-based plugins with a YAML manifest, extension points and isolated class loaders
* Configurable security concept per plugin location (signature, checksum, insecure)
* Loading process designed so that the host can run it asynchronously/non-blockingly itself
* Lifecycle management including enable/disable and runtime error isolation per plugin

## 2. Initial Situation

* The `pluggiat` repository was an empty Kotlin/Gradle skeleton without source code
* Single-module setup (`build.gradle.kts`, `settings.gradle.kts`), group `org.pcsoft.framework`
* Kotlin 2.4.20, JVM toolchain 25, Dokka, Kover, license report, CycloneDX BOM already present
* For this feature the project remained a single-module setup, no split into e.g. `api`/`core`

## 3. Achieved State

* Plugins reside as JAR/ZIP at configurable locations and are scanned at runtime
* Every plugin has a manifest `META-INF/plugin.yml` or `plugin.yaml`, validated against a synchronized JSON schema and mapped onto synchronized data classes
* Extension points are declared generically via `extensions.<key>[]`; the host defines every extension point through a configuration class annotated with `@ExtensionPoint` (key, `exclusive` flag) that implements `ExtensionConfiguration<T>`; a decorator maps the raw YAML data, including the instantiated implementation, onto it; plugin developers only ever know the host plugin API interface `T`
* The scanner supports three load modes (`SINGLE_JAR`, `MULTI_JAR_WITH_OWN_FOLDER`, `ZIP_JAR`, default `ZIP_JAR`) and returns valid as well as invalid plugins with error messages
* Every plugin location is classified as `BUILTIN` or external and has a security concept in the form of an ordered, freely extensible fallback chain of `PluginSecurityStrategy` strategies, with a location-specific override of the whole chain; strategies shipped: no check, signature, checksum
* The signature strategy obtains the public key to check through its own, exchangeable `PublicKeyProviderStrategy`: truststore (Java `KeyStore`), direct `java.security.PublicKey`, online platform OpenPGP (RFC 9580, e.g. `keys.openpgp.org`)
* The host can explicitly request a force-load for a single plugin, which bypasses a failed security check in a targeted and traceable, logged way
* Plugins are loaded through isolated `URLClassLoader`s that prevent access to the host application's code via reflection, but deliberately expose an SDK whitelist configured by the host
* Plugin dependencies (`required`/`optional`) form a class loader dependency graph for targeted class visibility between plugins
* ID collisions between locations are resolved through a version comparison (Maven scheme) and a subsequent checksum check
* Every plugin goes through defined lifecycle phases (`onLoad`/`onEnable`/`onDisable`/`onUnload`); a deactivation always includes discarding the plugin class loader, a reactivation requires a complete reload including a renewed security check
* Every plugin has a persistent enabled/disabled status, managed through an exchangeable `PluginPersistenceStrategy` (implementations shipped: no persistence, host callback, file, database via JDBC)
* An exception escaping from an extension at runtime is handled by a configurable `ExceptionHandlingStrategy` (IGNORE/UNLOAD/CRASH per exception type, with a default matrix); UNLOAD permanently deactivates exactly this one plugin, not the whole application
* Every extension instance handed to the host is a proxy (JDK proxy for interfaces, ByteBuddy subclass for open classes) over the host plugin API; as a result only `PluginExecutionException`/`PluginFatalException` escape from an extension call, never a raw plugin exception
* A central entry point `PluginManager` (root package), configurable via a Kotlin builder, bundles the host-wide configuration and provides preconfigured core components (`PluginScanner`, `PluginSecurity`, `PluginLoader`)

**Subsequent deviation (after feature completion)**: The load mode `MULTI_JAR_WITH_OWN_FOLDER`
(`MultiJarWithOwnFolderScanStrategy`) was dropped. The scanner only knows `SINGLE_JAR` and
`ZIP_JAR` (default `ZIP_JAR`) any more; a plugin is a single JAR or a ZIP file holding its JARs.
As a result, the checksum list (`META-INF/plugin-checksums.txt`) of the signature strategy and the
multi-JAR handling of the checksum strategy were dropped as well. ZIP plugins are read directly and
not unpacked, no temp directory exists. The statements "three load modes" (sections 3 and 6) and
"unpacked ZIP plugins reside in the temp directory" (section 5) accordingly no longer apply.

## 4. Requirements

### Functional Requirements

* Mandatory manifest fields: `$version`, `id`, `name`, `version`, `minVersion`, `icon`
* Optional manifest fields: `description`, `author` (`name` mandatory, `mail` optional), `documentationUrl`, `sourceCodeUrl`, `copyright`, `license`
* `icon`: Base64-encoded, format detected via magic bytes (SVG, PNG, JPG, ...)
* `license`: free string, optionally matched against the SPDX identifier list
* `version` and `minVersion`: Maven version scheme, comparable
* Plugin dependencies in the manifest, each dependency `required` or `optional`
* Scanner result contains valid and invalid plugins (with error messages) as well as plugins in the status `PENDING_APPROVAL`
* Extension points without the `exclusive` flag: several entries of the same key from different plugins are simply merged into one list
* Extension points with the `exclusive` flag: if two plugins fill the same key, **both plugins are not loaded at all**, with a log warning including both plugin IDs and the key
* ID collision between locations: log warning, higher version wins; on equal versions a checksum comparison; on equal checksums an arbitrary choice, on differing checksums a security warning and neither of the two is loaded
* `minVersion` check against the version of the host software, plugins that are too new are not loaded
* Persistent enabled/disabled status per plugin ID; the status check happens BEFORE any class loading of the extension, a disabled plugin stays scanned, but none of its extension classes is loaded/instantiated
* Runtime error isolation via a configurable `ExceptionHandlingStrategy`; `UNLOAD` permanently deactivates the affected plugin and it must be reactivated manually
* Force-load: the framework user can explicitly force loading per plugin despite a failed security check; the operation is logged with the plugin ID and the reason of the originally failed check
* Reactivation of a deactivated plugin mandatorily triggers a renewed security check run before the actual reload

### Technical Requirements

* Kotlin, Gradle (see `development.md`)
* Manifest parsing via YAML with a synchronized JSON schema and data classes; both are maintained manually in parallel, consistency is secured through tests
* Class loader isolation: parent-last strategy towards the host application with a deliberately exposed SDK layer, passed in by the host as configuration
* Security concept as a strategy pattern: `PluginSecurityStrategy` interface, extensible exclusively through new implementations, no enum; the chain is checked strictly in configuration order, a security problem is only reported once ALL strategies of the chain have failed
* The framework does not commit to a concrete async API; all entry points are designed so that the host can call them blockingly or non-blockingly in the concurrency model of its choice
* Persistence strategy as a strategy pattern (`PluginPersistenceStrategy`), exception handling as a strategy pattern (`ExceptionHandlingStrategy`), enforcement of the error isolation through a proxy
* Central entry point `PluginManager` (root package) with a Kotlin builder DSL
* Consistent logging of the scan/load process with the log levels `INFO`/`WARN`/`DEBUG`/`ERROR`

## 5. Architecture

* Components:
    * **Manifest module**: YAML parsing, JSON schema, data classes, validation
    * **Extension module**: `ExtensionConfiguration<T>` interface, `@ExtensionPoint` annotation, host registry, decorator mapping with proxy delivery
    * **Scanner module**: location configuration, scan strategies per load mode, result model
    * **Security module**: `PluginSecurityStrategy` interface, fallback chain evaluation, strategies shipped
    * **Public key provider module**: `PublicKeyProviderStrategy` interface, implementations shipped
    * **Class loader module**: `PluginLoader` class, SDK whitelist configured by the host, dependency graph between plugin class loaders
    * **Persistence module**: `PluginPersistenceStrategy` interface, implementations shipped
    * **Lifecycle module**: lifecycle hooks, persistent enabled/disabled status, runtime error isolation, proxy enforcement
    * **PluginManager module**: central entry point in the root package, configurable via a Kotlin builder
    * **Orchestration/runtime module**: interplay of scanner → security → class loader → manifest/extension mapping → lifecycle, host-controlled concurrency, ID collision resolution, `minVersion` check, force-load entry point
* Data flow: plugin locations → scanner → security check → class loader creation → manifest deserialization + validation → enabled/disabled status check → extension decorator mapping with proxy delivery → lifecycle activation → result
* External interfaces (to be provided by the framework user): public key callback, expected checksum callback, `PluginPersistenceStrategy`, `ExceptionHandlingStrategy` (optional, otherwise default matrix), SDK whitelist configuration, force-load call per plugin
* Persistence: via the exchangeable `PluginPersistenceStrategy` (default `NoPersistenceStrategy`); unpacked ZIP plugins reside in the temp directory and are removed via `deleteOnExit`
* MkDocs structure (separation of audiences plugin developers/host integrators):
    * `index.md` — overview/core concepts
    * `plugin-development/manifest.md` — manifest fields + example
    * `plugin-development/extension-points.md` — creating/consuming an extension point + example
    * `plugin-development/dependencies.md` — required/optional dependencies, helper class pattern
    * `plugin-development/lifecycle.md` — lifecycle hooks from the plugin's point of view
    * `plugin-development/error-handling.md` — recommended exceptions, default matrix, proxy design rule, debugging hints
    * `host-integration/setup.md` — plugin locations, load modes, start/reload API
    * `host-integration/security.md` — security concepts, strategy chain + example
    * `host-integration/public-key-providers.md` — public key provider strategies + example
    * `host-integration/sdk-whitelist.md` — SDK whitelist configuration of the host
    * `host-integration/plugin-lifecycle-management.md` — enabled/disabled status, deactivation reasons, reactivation flow
    * `host-integration/persistence.md` — `PluginPersistenceStrategy`, implementations, production recommendation
    * `host-integration/plugin-manager.md` — `PluginManager`, builder DSL, preconfigured instances
    * `troubleshooting.md` — log level overview, error cases (ID collision, exclusive conflict, minVersion)

## 6. Feature Completion Criteria

* A plugin with a valid manifest is correctly detected and loaded through all three load modes and its extensions are available typed with an instantiated implementation
* A plugin with an invalid manifest or a failed security check is not loaded and appears with a comprehensible error message in the scan result
* A plugin with a changed checksum ends up in the status `PENDING_APPROVAL` and is only loaded after approval, without the framework itself blocking the scan
* Two plugin locations with a colliding ID are resolved unambiguously according to the defined version/checksum rules
* A plugin location can configure an ordered chain of several security strategies; a plugin counts as security-checked as soon as one strategy of the chain succeeds, and is only rejected once ALL strategies of the chain fail
* New security strategies can be added as a pure implementation of the `PluginSecurityStrategy` interface without changing framework code (no enum)
* The signature strategy can be operated optionally with a truststore, direct or OpenPGP public key provider; an unresolvable OpenPGP key leads to a comprehensible failure, not to a crash
* A plugin cannot access host-internal code via reflection, but can access the SDK whitelist configured by the host
* Plugin dependencies (`required`/`optional`) are considered correctly during loading, including cycle detection
* A plugin with a `minVersion` above the current host version is not loaded
* A plugin permanently deactivated via `PluginPersistenceStrategy` loads no extension classes and has no active class loader, but stays visible in the scan result
* An exception escaping from an extension call is handled according to the `ExceptionHandlingStrategy`; `UNLOAD` automatically permanently deactivates only the affected plugin (must be reactivated manually), without impairing the rest of the application
* Every extension instance handed to the host is a proxy instance; only `PluginExecutionException`/`PluginFatalException` escape from the host plugin API, never a raw plugin exception
* A reactivation of a deactivated plugin only leads to an actual reload after a renewed successful security check
* If two plugins fill the same exclusive extension point, both are not loaded at all and a comprehensible log warning is issued
* The public API of the framework can be used both blockingly and from a concurrent context chosen by the host
* A plugin with a failed security check can still be loaded through an explicit force-load call of the host, the operation being logged comprehensibly
* `PluginManager` provides preconfigured `scanner`/`security`/`loader` instances from a single host-wide configuration via the Kotlin builder DSL
