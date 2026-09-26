# Schnellstart

Der kleinstmögliche pluggiat-Host: ein Plugin-Verzeichnis, keine Sicherheitsprüfung (nur für die
lokale Entwicklung), ein Erweiterungspunkt, ein `scan()`.

## 1. Abhängigkeit hinzufügen

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

Das Maven-Äquivalent und die benötigten GitHub-Packages-Zugangsdaten siehe
[Nutzung der Artefakte](index.de.md#nutzung-der-artefakte).

## 2. Einen Erweiterungspunkt deklarieren

Die eigene Plugin-API - der einzige pluggiat-unabhängige Typ, den ein Plugin-Autor kennen muss:

```kotlin
interface Greeter {
    fun greet(): String
}
```

Zusammen mit einer host-seitigen Konfigurationsklasse (siehe
[Erweiterungspunkte definieren](host-integration/extension-points.de.md)):

```kotlin
import org.pcsoft.framework.pluggiat.extension.ExtensionConfiguration
import org.pcsoft.framework.pluggiat.extension.ExtensionPoint
import kotlin.reflect.KClass

@ExtensionPoint(key = "greeters", exclusive = false)
data class GreeterConfig(
    override val implementation: KClass<out Greeter>,
) : ExtensionConfiguration<Greeter>
```

## 3. `PluginManager` konfigurieren und bauen

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

`InsecureSecurityStrategy` führt überhaupt keine Prüfung durch - für einen ersten Lauf gegen
selbst geschriebene Plugins in Ordnung, aber vor der Annahme fremder Plugins zu ersetzen; siehe
[Sicherheit](host-integration/security.de.md).

## 4. Plugins scannen, laden und nutzen

```kotlin
manager.scan()

val problems = manager.scanResults.filterNot { it.status.name == "LOADED" }
problems.forEach { println("Not loaded: ${it.path} (${it.status}: ${it.errorMessage})") }

val greeters: List<Greeter> = manager.getExtensions("greeters")
greeters.forEach { println(it.greet()) }
```

Ein JAR mit `META-INF/plugin.yml` und einer `Greeter`-Implementierung ins Verzeichnis `plugins/`
legen (Manifestformat siehe [Plugin-Entwicklung: Manifest](plugin-development/manifest.de.md)) - nach
dem nächsten `scan()` erscheint es hier.

## Wie geht es weiter

* [Vollständiges Beispiel](host-integration/example.de.md) - ein realistischer Host mit mehreren
  Verzeichnissen, signaturbasierter Sicherheit, Persistenz und einem Freigabe-Ablauf für abgelehnte
  Plugins
* [PluginManager](host-integration/plugin-manager.de.md) - die vollständige Konfigurationsoberfläche
  (`reload`/`unload`/`forceLoad`, Id-Kollisionen, `minVersion`)
* [Laufzeit-Sandbox](host-integration/sandbox.de.md) - wer einschränken will, was ein geladenes Plugin
  zur Laufzeit tun darf, sollte dies zuerst lesen: es erfordert den JVM-Start-Parameter `-javaagent`
* [Plugin-Entwicklung: Vollständiges Beispiel](plugin-development/example.de.md) - ein Plugin für einen
  Host wie den obigen schreiben
