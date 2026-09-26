# pluggiat

<p align="center">
  <img src="docs/docs/assets/images/logo.png" alt="pluggiat logo" width="368" />
</p>

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
* Optional `IntegrityProtectedPersistenceStrategy` decorator: HMAC-protects every stored value with
  a `SecureRandom`-generated key, detecting direct tampering with the underlying storage
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
* `PluginSandbox`: a host-wide facade for a plugin's runtime sandbox, configurable per
  `PluginLocation` (`sandboxOverride`) or globally per location type (`defaultSandboxPolicy`);
  bytecode API mediation (filesystem/network/reflection/process-start/`System.exit`) via a Java
  agent is implemented, with thread/time-limit governance and process isolation following in later
  releases. **Requires a `-javaagent:<path-to-this-jar>` JVM start parameter** as soon as any policy
  restricts an API category - see [Runtime sandbox](docs/docs/host-integration/sandbox.md)

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

> **Embedding pluggiat in a host application?** As soon as you configure a restrictive
> `PluginSandboxPolicy`, the host JVM must be started with this module's own JAR as a Java agent
> (`-javaagent:<path-to-pluggiat-jar>`) - see [Runtime sandbox](docs/docs/host-integration/sandbox.md).

## Consuming the artifacts

pluggiat is published to GitHub Packages under `org.pcsoft.framework:pluggiat`. Consuming it
requires a GitHub account with a personal access token that has the `read:packages` scope, since
GitHub Packages requires authentication even for public repositories.

### Gradle

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/KleinerHacker/pluggiat")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.pcsoft.framework:pluggiat:0.1.0")
}
```

`gpr.user`/`gpr.key` can be set in `~/.gradle/gradle.properties`, or `GITHUB_ACTOR`/`GITHUB_TOKEN`
as environment variables.

### Maven

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/KleinerHacker/pluggiat</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.pcsoft.framework</groupId>
        <artifactId>pluggiat</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

The `github` server's credentials (username plus a personal access token with `read:packages`) must
be configured in `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_GITHUB_TOKEN</password>
    </server>
</servers>
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
| Persistence integrity protection (HMAC decorator)                     | implemented |
| Orchestration runtime (`PluginManager`, ID collisions, `minVersion` check, force-load) | implemented |
| Runtime sandbox facade and policy configuration; bytecode API mediation via Java agent (`PluginSandbox`) | in progress |

All planned features of the initial feature plan (FP-001) are implemented. The runtime sandbox
feature plan (`PluginSandbox` and its concrete enforcers) is in progress; see the sandbox row above.
