# Vollständiges Beispiel: pluggiat in eine Report-Anwendung einbetten

Diese Seite führt durch das Einbetten von pluggiat in eine fiktive Host-Anwendung, "ReportApp", und
verbindet Plugin-Verzeichnisse, den von ihr angebotenen Erweiterungspunkt, ihre
Sicherheitskonfiguration, Persistenz, die SDK-Whitelist und die Lifecycle-Verwaltung zu einem
zusammenhängenden Aufbau. Siehe
[Plugin-Entwicklung: vollständiges Beispiel](../plugin-development/example.md) für ein Plugin, das
genau gegen diesen Host geschrieben ist.

## Das Szenario

ReportApp liefert ein eingebautes Plugin mit und lässt Benutzer zusätzliche Plugins von Drittanbietern
installieren:

* `plugins/builtin/` - mit der Anwendung selbst ausgeliefert, immer vertrauenswürdig.
* `plugins/external/` - ein vom Benutzer beschreibbarer Ordner für Drittanbieter-Plugins, verpackt
  als signierte `.zip`-Dateien; unsignierte oder manipulierte werden standardmäßig abgelehnt, mit
  einem expliziten Weg zur Benutzer-Übersteuerung.
* Plugins tragen ein "Report"-Exportformat über einen `exporters`-Erweiterungspunkt bei.
* Der Aktiviert/deaktiviert-Status sowie alle vom Benutzer genehmigten Sicherheits-Übersteuerungen
  müssen einen Neustart der Anwendung überstehen.

## Den Erweiterungspunkt definieren

Die eigene Plugin-API von ReportApp - der einzige pluggiat-unabhängige Typ, den ein
Plugin-Autor jemals kennen muss - ist ein einfaches Interface:

```kotlin
package com.example.reportapp.api

interface Exporter {
    fun export(data: List<Row>): ByteArray
}
```

Kombiniert mit einer host-definierten Konfigurationsklasse, gemäß
[Erweiterungspunkte definieren](extension-points.md):

```kotlin
package com.example.reportapp.api

import org.pcsoft.framework.pluggiat.extension.ExtensionConfiguration
import org.pcsoft.framework.pluggiat.extension.ExtensionPoint
import kotlin.reflect.KClass

@ExtensionPoint(key = "exporters", exclusive = false)
data class ExporterConfig(
    override val implementation: KClass<out Exporter>,
    val fileExtension: String,
    val displayName: String,
) : ExtensionConfiguration<Exporter>
```

## SDK-Whitelist

Nur `com.example.reportapp.api` (dieses `Exporter`-Interface sowie das `Row`-DTO, über das Plugins
Daten austauschen) soll für Plugin-Code jemals sichtbar sein - nichts anderes in den eigenen Paketen
von ReportApp sollte erreichbar sein, gemäß [SDK-Whitelist](sdk-whitelist.md):

```kotlin
sdkWhitelistEntry {
    packageName = "com.example.reportapp.api"
}
```

## Sicherheit: unterschiedliches Vertrauen je Verzeichnis

Das eingebaute Verzeichnis benötigt keinen Schutz - es ist der eigene Code von ReportApp. Das
externe Verzeichnis muss standardmäßig alles ablehnen, was nicht mit dem Herausgeberschlüssel von
ReportApp signiert ist, während ein Benutzer ein bestimmtes Plugin danach weiterhin explizit
genehmigen können muss. Gemäß [Sicherheit](security.md#welche-strategie-sollte-ich-verwenden):

```kotlin
val reportAppPublicKey: PublicKey = loadReportAppSigningKey()

defaultSecurityChain {
    type = PluginLocationType.BUILTIN
    addStrategy(InsecureSecurityStrategy())
}
defaultSecurityChain {
    type = PluginLocationType.EXTERNAL
    addStrategy(SignatureSecurityStrategy(DirectPublicKeyProviderStrategy(reportAppPublicKey)))
}
```

Ein Kandidat, der an dieser Kette scheitert, wird als `SECURITY_PROBLEM` gemeldet, nicht
stillschweigend abgelehnt - siehe [den Freigabeablauf](#der-freigabeablauf-fur-ein-abgelehntes-plugin)
unten für das, was ReportApp damit macht.

## Persistenz: einen Neustart überstehen

Sowohl der Aktiviert/deaktiviert-Status als auch (sobald ein Benutzer ein unsigniertes Plugin
genehmigt) die daraus resultierende persistierte Sicherheitsausnahme müssen einen Neustart
überstehen. Für die Bedürfnisse von ReportApp reicht eine einzelne Datei, gemäß
[Persistenz](persistence.md#filepersistencestrategy):

```kotlin
val persistence = FilePersistenceStrategy(
    path = Paths.get(System.getProperty("user.home"), ".reportapp", "plugin-state.properties"),
)
```

## Den `PluginManager` zusammensetzen

Alles Obige kommt in einem einzigen `pluginManager { ... }`-Block zusammen, gemäß
[PluginManager](plugin-manager.md):

```kotlin
val manager = pluginManager {
    location {
        path = Paths.get(installDir, "plugins", "builtin")
        type = PluginLocationType.BUILTIN
        scanStrategy = SingleJarScanStrategy()
    }
    location {
        path = Paths.get(userDataDir, "plugins", "external")
        type = PluginLocationType.EXTERNAL
        scanStrategy = ZipJarScanStrategy() // Standard, hier der Klarheit halber angegeben
    }
    defaultSecurityChain {
        type = PluginLocationType.BUILTIN
        addStrategy(InsecureSecurityStrategy())
    }
    defaultSecurityChain {
        type = PluginLocationType.EXTERNAL
        addStrategy(SignatureSecurityStrategy(DirectPublicKeyProviderStrategy(reportAppPublicKey)))
    }
    dependencyStrategy = UnrestrictedPluginDependencyStrategy()
    persistenceStrategy = persistence
    exceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    sdkWhitelistEntry {
        packageName = "com.example.reportapp.api"
    }
    extensionPoint(ExporterConfig::class)
    hostVersion = ReportApp.VERSION // z. B. "1.4.2" - wird gegen das minVersion jedes Plugins geprüft
}
```

## Scannen beim Start

```kotlin
manager.scan()

val problems = manager.scanResults.filterNot { it.status == PluginScanStatus.LOADED }
for (problem in problems) {
    logger.warn("Plugin at {} not loaded: {} ({})", problem.path, problem.status, problem.errorMessage)
}

val exporters: List<Exporter> = manager.getExtensions("exporters")
exportMenu.populate(exporters.map { it to (manager.registry.registrationFor("exporters")) })
```

Dieser einzige Aufruf löst ID-Kollisionen zwischen `builtin`/`external` auf, prüft das `minVersion`
jedes Kandidaten gegen `ReportApp.VERSION`, lädt alles Bestandene in Abhängigkeitsreihenfolge und
aktiviert deren Erweiterungen - siehe
[ID-Kollisionen und minVersion](plugin-manager.md#id-kollisionen-und-minversion).

## Der Freigabeablauf für ein abgelehntes Plugin

Fortsetzung des [Report-Exporter-Beispiels](../plugin-development/example.md): Ein Benutzer lädt
einen unsignierten Drittanbieter-Build von `com.example.report-exporter-plugin` herunter und legt
ihn in `plugins/external/` ab. Beim nächsten `scan()` kommt er als `SECURITY_PROBLEM` zurück - die
Signaturprüfung ist fehlgeschlagen, und es gibt keine weitere Strategie in der Kette, auf die
zurückgegriffen werden könnte. Gemäß
[dem Host-Freigabeablauf](security.md#host-freigabeablauf-nach-einem-security_problem):

```kotlin
val rejected = manager.scanResults.first { it.status == PluginScanStatus.SECURITY_PROBLEM }

if (userConfirmsDialog("'${rejected.manifest?.name}' is not signed by a known publisher. Load it anyway?")) {
    manager.forceLoad(rejected.manifest!!.id)
    // Hier gibt es keine PersistableSecurityStrategy (SignatureSecurityStrategy hat keinen
    // persistierbaren Zustand), also die Übersteuerung stattdessen mit einer generischen Ausnahme dauerhaft machen:
    manager.forceLoad(rejected.manifest!!.id, persistException = true)
}
```

Ab diesem Zeitpunkt überspringt jedes zukünftige `scan()`/`reload()` für diese konkrete Plugin-ID
ihre Sicherheitskette vollständig - jedes Mal als `WARN` protokolliert, sodass dies in den eigenen
Logs von ReportApp sichtbar und später über
[Fehlersuche](troubleshooting.md#referenz-pluginscanstatus) nachvollziehbar bleibt.

## Ein Plugin durch den Benutzer deaktivieren lassen

```kotlin
manager.unload("com.example.report-exporter-plugin")
```

Dies führt die `onDisable`/`onUnload`-Hooks des Plugins aus, persistiert `"enabled" = "false"` und
`"disabledReason" = "USER"` über `persistence` und schließt seinen Classloader - siehe
[Plugin-Lifecycle-Verwaltung](plugin-lifecycle-management.md#deaktivierungsgrund). Es später wieder
zu aktivieren ist ein einfaches `manager.reload("com.example.report-exporter-plugin")`, das zuerst
die Sicherheit erneut prüft (und für diese konkrete Plugin-ID dank der aus dem obigen
Freigabeablauf persistierten Ausnahme sofort wieder erfolgreich ist).
