# Vollständiges Beispiel: ein Report-Export-Plugin

Diese Seite führt anhand eines einzigen, realistischen Plugins von Anfang bis Ende durch alles, was
auf den vorherigen Seiten behandelt wurde: das Manifest, einen Beitrag zu einem Erweiterungspunkt,
eine erforderliche und eine optionale Abhängigkeit, Lifecycle-Hooks und Fehlerbehandlung. Es wird
angenommen, dass der Host einen `exporters`-Erweiterungspunkt anbietet, ähnlich dem, der als
Beispiel in dieser gesamten Dokumentation verwendet wird - siehe
[Host-Integration: vollständiges Beispiel](../host-integration/example.de.md) für die passende
Host-Seite genau dieses Szenarios.

## Das Szenario

`com.example.report-exporter-plugin` fügt der Host-Anwendung ein "Report"-Exportformat hinzu. Es:

* benötigt `com.example.charting-plugin`, um ein eingebettetes Diagramm zu rendern (kann ohne es
  nicht geladen werden),
* integriert sich optional mit `com.example.watermark-plugin`, sofern vorhanden, um dem erzeugten
  Report ein Wasserzeichen aufzudrücken,
* hält über mehrere Aufrufe hinweg einen offenen Ausgabestrom und muss ihn beim Herunterfahren
  freigeben - ein guter Anwendungsfall für `PluginLifecycle`,
* möchte, dass ein fehlgeschlagener Export als behebbarer Fehler erscheint, nicht das gesamte
  Plugin deaktiviert.

## Das Manifest

```yaml
$version: 1
id: com.example.report-exporter-plugin
name: Report Exporter
version: "2.1.0"
minVersion: "1.4.0"
icon: <base64-encoded PNG>
description: Exports data as a formatted PDF/HTML report, with optional watermarking.
author:
  name: Jane Doe
  mail: jane.doe@example.com
links:
  documentation: https://example.com/report-exporter/docs
  sourceCode: https://example.com/report-exporter
legal:
  copyright: "Copyright (c) 2026 Jane Doe"
  license: Apache-2.0
dependencies:
  - id: com.example.charting-plugin
    required: true
  - id: com.example.watermark-plugin
    required: false
extensions:
  exporters:
    - implementation: com.example.report.ReportExporter
      fileExtension: report.html
      displayName: "Report (HTML)"
```

Zwei Dinge sind bemerkenswert, beide bereits auf früheren Seiten behandelt:

* `minVersion: "1.4.0"` bedeutet, dass dieses Plugin sich weigert, gegen einen älteren Host zu
  laden - sofern der Host eine eigene `hostVersion` konfiguriert hat; ohne sie entfällt die
  Prüfung - siehe [Manifest](manifest.de.md#pflichtfelder).
* Die beiden `dependencies`-Einträge unterscheiden sich nur in `required`, und genau das bestimmt,
  ob ein fehlendes `com.example.watermark-plugin` lediglich die Wasserzeichnung deaktiviert oder das
  gesamte Plugin ungültig macht - siehe [Abhängigkeiten](dependencies.de.md#required-vs-optional).

## Die Erweiterungsimplementierung

Das `Exporter`-Interface des Hosts (vom Host definiert, siehe das host-seitige Beispiel) wird als
Klasse implementiert, die zusätzlich `PluginLifecycle` für die Ressourcenverwaltung implementiert:

```kotlin
package com.example.report

import org.pcsoft.framework.pluggiat.PluginLifecycle
import java.io.OutputStream

class ReportExporter : Exporter, PluginLifecycle {

    private lateinit var renderPool: ReportRenderPool

    override fun onLoad() {
        // Cheap, side-effect-free setup - runs after every dependency of this plugin completed onLoad and onEnable.
        renderPool = ReportRenderPool(size = 4)
    }

    override fun onEnable() {
        // Safe to do real work here - the required dependencies already ran onLoad and onEnable.
        renderPool.warmUp()
    }

    override fun export(data: List<Row>): ByteArray {
        val chart = ChartingApi.renderChart(data) // from the required com.example.charting-plugin
        val html = renderPool.render(data, chart)
        return if (WatermarkHelper.isAvailable()) {
            WatermarkHelper.stamp(html) // only touches the optional dependency's types
        } else {
            html
        }
    }

    override fun onDisable() {
        renderPool.drainInFlight()
    }

    override fun onUnload() {
        renderPool.close()
    }
}
```

### Direkte Verwendung der erforderlichen Abhängigkeit

`com.example.charting-plugin` ist `required: true`, sein Fehlen macht dieses Plugin also bereits
ungültig, bevor dieser Code überhaupt ausgeführt wird - `ChartingApi` kann direkt referenziert
werden, genau wie jeder andere Typ, ohne dass eine Vorhandenseinsprüfung nötig wäre.

### Verwendung der optionalen Abhängigkeit über eine Helferklasse

`com.example.watermark-plugin` ist `required: false` und möglicherweise nicht installiert. Gemäß dem
[Helferklassen-Muster](dependencies.de.md#helferklassen-muster-fur-optionale-abhangigkeiten) wird
`WatermarkApi` (der Typ der optionalen Abhängigkeit) niemals direkt innerhalb von `ReportExporter`
selbst referenziert - nur innerhalb des kleinen `WatermarkHelper`-Objekts, sodass die eigene
Klassendatei von `ReportExporter` `WatermarkApi` nie zum Auflösen benötigt:

```kotlin
package com.example.report

internal object WatermarkHelper {
    fun isAvailable(): Boolean = WatermarkPluginRegistry.isLoaded("com.example.watermark-plugin")

    fun stamp(html: ByteArray): ByteArray {
        val api: WatermarkApi = WatermarkApiImpl() // resolved only when this line actually runs
        return api.applyWatermark(html)
    }
}
```

### Warum die Lifecycle-Aufteilung hier wichtig ist

`renderPool.warmUp()` geschieht in `onEnable`, nicht in `onLoad`: `onLoad` erzeugt nur den Pool,
sodass die teure Arbeit erst beginnt, wenn das Plugin vollständig eingerichtet ist und jede
Abhängigkeit ihre eigene Aktivierung abgeschlossen hat - siehe
[Lifecycle-Hooks: Aufrufreihenfolge](lifecycle.de.md#aufrufreihenfolge). Symmetrisch dazu leert
`onDisable` nur die laufende Arbeit, während `renderPool.close()` in `onUnload` liegt, sodass der
Pool noch nutzbar ist, während ausstehende Exporte abgeschlossen werden. Der Abbau ist jedoch nicht
über Plugins hinweg geordnet: verlassen Sie sich nicht darauf, dass ein anderes Plugin innerhalb
Ihres eigenen `onDisable`/`onUnload` noch nutzbar ist.

## Fehlerbehandlung in der Praxis

`export` kann aus Gründen fehlschlagen, die nicht das gesamte Plugin lahmlegen sollten - z. B.
fehlerhafte Eingabezeilen - im Gegensatz zu Gründen, bei denen das Plugin gar nicht weiterarbeiten
kann - z. B. wenn die Initialisierung des Render-Pools fehlschlägt. Gemäß
[Fehlerbehandlung](error-handling.de.md):

```kotlin
override fun export(data: List<Row>): ByteArray {
    if (data.isEmpty()) {
        // reaches the caller unchanged, the strategy is not consulted: plugin stays active
        throw PluginExecutionException("Cannot export an empty report")
    }
    val pool = renderPool.takeIfHealthy()
        ?: throw IllegalStateException("Render pool is corrupted, cannot recover") // any other unchecked exception: UNLOAD (standard matrix)

    return pool.render(data, ChartingApi.renderChart(data))
}
```

Eine selbst geworfene `PluginFatalException` würde das Plugin *nicht* entladen - sie erreicht den
Aufrufer unverändert, genau wie `PluginExecutionException`. Um einen nicht behebbaren Zustand zu
signalisieren, lassen Sie wie oben eine andere Unchecked Exception entweichen; der Host erhält dann
eine `PluginFatalException`, die diese umhüllt.

Ein Aufrufer auf Host-Seite erhält niemals eine direkte Referenz auf `ReportExporter` - nur den
Runtime-Enforcement-Proxy - sodass auch jede andere, unerwartete Ausnahme, die `export` entweichen
lässt (etwa eine `NullPointerException` durch einen Bug), trotzdem abgefangen, protokolliert und
über die konfigurierte Fehlerbehandlungsmatrix des Hosts aufgelöst wird, standardmäßig mit `UNLOAD`
für eine Unchecked Exception, die das Plugin nicht vorhergesehen hat.

## Zusammenfassung des Ablaufs

1. Der Host scannt die `.zip`/`.jar` des Plugins und validiert sein Manifest.
2. `minVersion` (sofern der Host eine `hostVersion` konfiguriert hat) und die Abhängigkeit zu
   `com.example.charting-plugin` werden geprüft, bevor überhaupt eine Klasse dieses Plugins
   geladen wird.
3. `PluginLifecycle.onLoad` läuft, dann `onEnable` - beide, bevor `export` jemals aufgerufen wird.
4. Jeder `export`-Aufruf läuft über den Runtime-Enforcement-Proxy; `PluginExecutionException` und
   `PluginFatalException` erreichen den Aufrufer unverändert und halten das Plugin am Laufen,
   während eine unerwartete Unchecked Exception es entlädt.
5. Wenn der Host das Plugin über `PluginManager.unload` entlädt oder ein Laufzeitfehler zu `UNLOAD`
   aufgelöst wird, laufen `onDisable` und dann `onUnload`, und der Classloader des Plugins wird
   verworfen. pluggiat registriert keinen JVM-Shutdown-Hook, daher muss der Host das Plugin selbst
   entladen, wenn die Hooks beim Beenden der Anwendung laufen sollen.
