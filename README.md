# pluggiat

pluggiat is a plugin manager system for the JVM, written in Kotlin. It discovers, loads and
manages plugins for a host application, so the host itself does not have to implement plugin
discovery, isolation or lifecycle handling.

## Features

* YAML plugin manifest (`META-INF/plugin.yml`/`.yaml`) with a synchronized JSON Schema and Kotlin
  data classes, icon format auto-detection and optional SPDX license matching
* Generic extension point mechanism: host-defined, annotated configuration classes mapping manifest
  entries onto typed, instantiated implementations, without plugin code ever touching pluggiat types
* Exclusive extension points with conflict detection between competing plugins
* Plugin scanner with three load modes - `SINGLE_JAR`, `MULTI_JAR_WITH_OWN_FOLDER` and `ZIP_JAR`
  (default) _(planned)_
* Distinction between builtin and external plugin locations _(planned)_
* Configurable per-location security concept - `PLAIN`, `MUST_SIGN` (signature via a public key
  resolved through a pluggable `PublicKeyProviderStrategy`: Java truststore, directly supplied key,
  or an OpenPGP keyserver) and `CHECKSUM` (approval workflow for unknown/changed checksums)
  _(planned)_
* Isolated plugin classpaths via parent-last `PluginClassLoader`s with a host-configured SDK
  whitelist, preventing plugins from reflecting into host-internal code; loading is unconditional,
  so a host can knowingly load a plugin that failed its security check
* Plugin dependency graph with required and optional dependencies between plugins, cycle detection,
  and a configurable `PluginDependencyStrategy` governing cross-location visibility
* Plugin lifecycle hooks (`onLoad`/`onEnable`/`onDisable`/`onUnload`) and a persistent
  enabled/disabled status, checked before any extension class of a disabled plugin is resolved
* Generic, pluggable `PluginPersistenceStrategy` (no-op, custom callback, file in four formats,
  JDBC) backing both the checksum security strategy and the enabled/disabled status
* Runtime error isolation: every extension call is enforced through a runtime proxy
  (`java.lang.reflect.Proxy`/ByteBuddy) resolving escaping exceptions via a configurable
  `ExceptionHandlingStrategy` (`IGNORE`/`UNLOAD`/`CRASH`) - an unhandled exception forces only that
  plugin to be deactivated, not the whole host application
* Central, stateful `PluginManager` entry point with a Kotlin builder DSL bundling host-wide
  configuration; `scan()`/`reload()`/`unload()`/`forceLoad()` orchestrate the full scan-load-enable
  flow and typed `getExtensions<T>`/`getFirstExtension<T>` expose the active implementations
* Cross-location plugin ID collision resolution (version-based, no further tie-breaking) and a
  `minVersion` compatibility check against the host application
* Two ways to make a force-loaded plugin's override stick: `PluginManager.write<T>` for a
  `PersistableSecurityStrategy` (e.g. the checksum strategy persisting its own accepted checksum), or
  a generic, persistent security exception via `forceLoad(pluginId, persistException = true)`

## AI transparency notice

Parts of the source code, the tests and this documentation were generated with the help of AI
coding assistants. Every generated contribution is reviewed, adapted and accepted by a human
maintainer before it is released; the maintainers remain responsible for the published software.

This notice is published in the spirit of the transparency requirements of the European Union's
Artificial Intelligence Act (Regulation (EU) 2024/1689).

## Checkout, build and run

```bash
git clone https://github.com/KleinerHacker/pluggiat.git
cd pluggiat
./gradlew build
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Consuming the artifacts

pluggiat is published as a Maven artifact:

```kotlin
dependencies {
    implementation("org.pcsoft.framework:pluggiat:<version>")
}
```

## Documentation

* [MkDocs documentation](https://kleinerhacker.github.io/pluggiat/latest/)
* [API documentation](https://kleinerhacker.github.io/pluggiat/latest/dokka/html/index.html)
* [Licence report](https://kleinerhacker.github.io/pluggiat/latest/licences/index.html)

## Implementation status

| Feature                                                          | State   |
|-------------------------------------------------------------------|---------|
| Plugin manifest (YAML, JSON Schema, data classes)                  | implemented |
| Extension point mechanism (annotation, decorator, exclusive slots) | implemented |
| Plugin scanner and load modes                                      | planned |
| Security concepts (`PLAIN`/`MUST_SIGN`/`CHECKSUM`)                  | planned |
| Isolated classpaths and dependency graph                           | implemented |
| Plugin lifecycle and runtime error isolation                       | implemented |
| Orchestration runtime (ID collisions, `minVersion` check)           | implemented |
