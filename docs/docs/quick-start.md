# Quick start

The smallest possible pluggiat host: one plugin location, no security (local development only),
one extension point, one `scan()`.

## 1. Add the dependency

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

See [Consuming the artifacts](index.md#consuming-the-artifacts) for the Maven equivalent and the
required GitHub Packages credentials.

## 2. Declare an extension point

Your own plugin API - the only pluggiat-unrelated type a plugin author needs to know:

```kotlin
interface Greeter {
    fun greet(): String
}
```

Paired with a host-defined configuration class (see
[Defining extension points](host-integration/extension-points.md)):

```kotlin
import org.pcsoft.framework.pluggiat.extension.ExtensionConfiguration
import org.pcsoft.framework.pluggiat.extension.ExtensionPoint
import kotlin.reflect.KClass

@ExtensionPoint(key = "greeters", exclusive = false)
data class GreeterConfig(
    override val implementation: KClass<out Greeter>,
) : ExtensionConfiguration<Greeter>
```

## 3. Configure and build a `PluginManager`

```kotlin
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Paths

val manager = pluginManager {
    location {
        path = Paths.get("plugins")
        type = PluginLocationType.EXTERNAL
    }
    defaultSecurityChain {
        type = PluginLocationType.EXTERNAL
        addStrategy(InsecureSecurityStrategy()) // local development only, see Security
    }
    extensionPoint(GreeterConfig::class)
}
```

`InsecureSecurityStrategy` performs no check at all - fine for a first run against plugins you wrote
yourself, but replace it before accepting plugins from anyone else; see
[Security](host-integration/security.md).

## 4. Scan, load and use plugins

```kotlin
manager.scan()

val problems = manager.scanResults.filterNot { it.status.name == "LOADED" }
problems.forEach { println("Not loaded: ${it.path} (${it.status}: ${it.errorMessage})") }

val greeters: List<Greeter> = manager.getExtensions("greeters")
greeters.forEach { println(it.greet()) }
```

Drop a JAR containing `META-INF/plugin.yml` and a `Greeter` implementation into `plugins/` (see
[Plugin development: manifest](plugin-development/manifest.md) for the manifest format) and it
shows up here after the next `scan()`.

## Next steps

* [Complete example](host-integration/example.md) - a realistic host with multiple locations,
  signature-based security, persistence and an approval flow for rejected plugins
* [PluginManager](host-integration/plugin-manager.md) - the full configuration surface
  (`reload`/`unload`/`forceLoad`, id collisions, `minVersion`)
* [Runtime sandbox](host-integration/sandbox.md) - if you plan to restrict what a loaded plugin can
  do at runtime, read this first: it requires a `-javaagent` JVM start parameter
* [Plugin development: complete example](plugin-development/example.md) - writing a plugin against
  a host like the one above
