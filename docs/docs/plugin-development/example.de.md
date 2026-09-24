# Vollständiges Beispiel: ein Report-Export-Plugin

Diese Seite führt anhand eines einzigen, realistischen Plugins von Anfang bis Ende durch alles, was
auf den vorherigen Seiten behandelt wurde: das Manifest, einen Beitrag zu einem Erweiterungspunkt,
eine erforderliche und eine optionale Abhängigkeit, Lifecycle-Hooks und Fehlerbehandlung. Es wird
angenommen, dass der Host einen `exporters`-Erweiterungspunkt anbietet, ähnlich dem, der als
Beispiel in dieser gesamten Dokumentation verwendet wird - siehe
[Host-Integration: vollständiges Beispiel](../host-integration/example.md) für die passende
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
id: com.example.report-exporter-plugin
name: Report Exporter
version: "2.1.0"
minVersion: "1.4.0"
icon: <base64-kodiertes PNG>
description: Exportiert Daten als formatierten PDF-/HTML-Report, mit optionaler Wasserzeichnung.
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
  laden - siehe [Manifest](manifest.md#pflichtfelder).
* Die beiden `dependencies`-Einträge unterscheiden sich nur in `required`, und genau das bestimmt,
  ob ein fehlendes `com.example.watermark-plugin` lediglich die Wasserzeichnung deaktiviert oder das
  gesamte Plugin ungültig macht - siehe [Abhängigkeiten](dependencies.md#required-vs-optional).

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
        // Günstiges, nebenwirkungsfreies Setup - läuft für jede Abhängigkeit, bevor irgendein onEnable läuft.
        renderPool = ReportRenderPool(size = 4)
    }

    override fun onEnable() {
        // Hier ist echte Arbeit sicher möglich - alle erforderlichen Abhängigkeiten dieses Plugins haben bereits onLoad durchlaufen.
        renderPool.warmUp()
    }

    override fun export(data: List<Row>): ByteArray {
        val chart = ChartingApi.renderChart(data) // aus dem erforderlichen com.example.charting-plugin
        val html = renderPool.render(data, chart)
        return if (WatermarkHelper.isAvailable()) {
            WatermarkHelper.stamp(html) // berührt nur die Typen der optionalen Abhängigkeit
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
[Helferklassen-Muster](dependencies.md#helferklassen-muster-fur-optionale-abhangigkeiten) wird
`WatermarkApi` (der Typ der optionalen Abhängigkeit) niemals direkt innerhalb von `ReportExporter`
selbst referenziert - nur innerhalb des kleinen `WatermarkHelper`-Objekts, sodass die eigene
Klassendatei von `ReportExporter` `WatermarkApi` nie zum Auflösen benötigt:

```kotlin
package com.example.report

internal object WatermarkHelper {
    fun isAvailable(): Boolean = WatermarkPluginRegistry.isLoaded("com.example.watermark-plugin")

    fun stamp(html: ByteArray): ByteArray {
        val api: WatermarkApi = WatermarkApiImpl() // wird erst aufgelöst, wenn diese Zeile tatsächlich läuft
        return api.applyWatermark(html)
    }
}
```

### Warum die Lifecycle-Aufteilung hier wichtig ist

`renderPool.warmUp()` geschieht in `onEnable`, nicht in `onLoad`. Würde eine zukünftige Version
dieses Plugins eine eigene optionale Abhängigkeit zur `onLoad`-Ausgabe eines anderen Plugins
hinzufügen, könnte teure Arbeit in `onLoad` laufen, bevor diese Abhängigkeit die Chance hatte, sich
zu initialisieren - siehe [Lifecycle-Hooks: Aufreihenfolge](lifecycle.md#aufreihenfolge). Symmetrisch
dazu liegt `renderPool.close()` in `onUnload`, nicht in `onDisable`, sodass das eigene `onDisable`
eines abhängigen Plugins bis zum Abschluss des Abbaus weiterhin einen funktionierenden
`ReportExporter` sieht.

## Fehlerbehandlung in der Praxis

`export` kann aus Gründen fehlschlagen, die nicht das gesamte Plugin lahmlegen sollten - z. B.
fehlerhafte Eingabezeilen - im Gegensatz zu Gründen, bei denen das Plugin gar nicht weiterarbeiten
kann - z. B. wenn die Initialisierung des Render-Pools fehlschlägt. Gemäß
[Fehlerbehandlung](error-handling.md):

```kotlin
override fun export(data: List<Row>): ByteArray {
    if (data.isEmpty()) {
        throw PluginExecutionException("Cannot export an empty report") // IGNORE: Plugin bleibt aktiv
    }
    val pool = renderPool.takeIfHealthy()
        ?: throw PluginFatalException("Render pool is corrupted, cannot recover") // UNLOAD

    return pool.render(data, ChartingApi.renderChart(data))
}
```

Ein Aufrufer auf Host-Seite erhält niemals eine direkte Referenz auf `ReportExporter` - nur den
Runtime-Enforcement-Proxy - sodass auch jede andere, unerwartete Ausnahme, die `export` entweichen
lässt (etwa eine `NullPointerException` durch einen Bug), trotzdem abgefangen, protokolliert und
über die konfigurierte Fehlerbehandlungsmatrix des Hosts aufgelöst wird, standardmäßig mit `UNLOAD`
für eine unerwartete Unchecked Exception.

## Zusammenfassung des Ablaufs

1. Der Host scannt die `.zip`/`.jar` des Plugins und validiert sein Manifest.
2. `minVersion` und die Abhängigkeit zu `com.example.charting-plugin` werden geprüft, bevor
   überhaupt eine Klasse dieses Plugins geladen wird.
3. `PluginLifecycle.onLoad` läuft, dann `onEnable` - beide, bevor `export` jemals aufgerufen wird.
4. Jeder `export`-Aufruf läuft über den Runtime-Enforcement-Proxy; `PluginExecutionException` hält
   das Plugin am Laufen, `PluginFatalException` (oder eine unerwartete Unchecked Exception)
   entlädt es.
5. Beim Herunterfahren des Hosts oder wenn das Plugin deaktiviert wird, laufen `onDisable` und dann
   `onUnload`, und der Classloader des Plugins wird verworfen.
