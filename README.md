# pluggiat

pluggiat is a plugin manager system for the JVM, written in Kotlin. It discovers, loads and
manages plugins for a host application, so the host itself does not have to implement plugin
discovery, isolation or lifecycle handling.

## Features

* YAML plugin manifest (`META-INF/plugin.yml`/`.yaml`), validated against a JSON Schema, with icon
  format auto-detection and optional SPDX license matching
* Generic extension point mechanism: host-defined, annotated configuration classes map manifest
  entries onto typed, instantiated implementations, without plugin code ever touching pluggiat
  types; exclusive extension points with conflict detection between competing plugins
* Plugin scanner with three load modes (`SingleJarScanStrategy`, `MultiJarWithOwnFolderScanStrategy`,
  `ZipJarScanStrategy` as default) and a builtin/external location distinction
* Configurable, per-location security strategy chain - no protection (`InsecureSecurityStrategy`),
  signature-based (`SignatureSecurityStrategy`, with a pluggable public-key provider: Java
  truststore, directly supplied key, or an OpenPGP keyserver) and checksum-based
  (`ChecksumSecurityStrategy`, host-managed approval of unknown/changed plugins)
* Isolated plugin classpaths via parent-last `PluginClassLoader`s with a host-configured SDK
  whitelist, preventing plugins from reflecting into host-internal code; loading is unconditional,
  so a host can knowingly load a plugin that failed its security check
* Plugin dependency graph with required and optional dependencies, cycle detection, and a
  configurable `PluginDependencyStrategy` governing cross-location visibility
* Plugin lifecycle hooks (`onLoad`/`onEnable`/`onDisable`/`onUnload`) and a persistent
  enabled/disabled status, checked before any extension class of a disabled plugin is resolved
* Generic, pluggable `PluginPersistenceStrategy` (no-op, custom callback, file in four formats,
  JDBC, or host object getter/setter) backing both the checksum security strategy and the
  enabled/disabled status
* Runtime error isolation: every extension call is enforced through a runtime proxy resolving
  escaping exceptions via a configurable `ExceptionHandlingStrategy` (`IGNORE`/`UNLOAD`/`CRASH`) -
  an unhandled exception forces only that plugin to be deactivated, not the whole host application
* Central, stateful `PluginManager` entry point with a Kotlin builder DSL bundling host-wide
  configuration; `scan()`/`reload()`/`unload()`/`forceLoad()` orchestrate the full scan-load-enable
  flow, and typed `getExtensions<T>`/`getFirstExtension<T>` expose the active implementations
* Cross-location plugin ID collision resolution (version-based, no further tie-breaking) and a
  `minVersion` compatibility check against the host application
* Two ways to make a force-loaded plugin's override stick: `PluginManager.write<T>` for a
  `PersistableSecurityStrategy` (e.g. the checksum strategy persisting its own accepted checksum),
  or a generic, persistent security exception via `forceLoad(pluginId, persistException = true)`

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

* [MkDocs documentation](https://kleinerhacker.github.io/pluggiat/latest/) - plugin development and
  host integration guides, including complete worked examples for both
* [API documentation](https://kleinerhacker.github.io/pluggiat/latest/dokka/html/index.html)
* [Licence report](https://kleinerhacker.github.io/pluggiat/latest/licences/index.html)

## Implementation status

| Feature                                                            | State       |
|---------------------------------------------------------------------|-------------|
| Plugin manifest (YAML, JSON Schema, data classes)                    | implemented |
| Extension point mechanism (annotation, aggregation, exclusive slots) | implemented |
| Plugin scanner and load modes                                        | implemented |
| Security strategy chain (insecure/signature/checksum) and public-key providers | implemented |
| Isolated classpaths and dependency graph                             | implemented |
| Plugin lifecycle and runtime error isolation                         | implemented |
| Persistence strategies                                                | implemented |
| Orchestration runtime (`PluginManager`, ID collisions, `minVersion` check, force-load) | implemented |

All planned features of the initial feature plan (FP-001) are implemented.
