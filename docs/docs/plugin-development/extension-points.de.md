# Erweiterungspunkte

Ein Plugin trägt Funktionalität zur Host-Anwendung über Erweiterungspunkte bei. Jeder
Erweiterungspunkt wird durch einen Schlüssel identifiziert (deklariert unter `extensions.<key>[]`
im Manifest, siehe [Plugin-Manifest](manifest.md)) und vom Host - nicht vom Plugin - definiert.

Als Plugin-Entwickler müssen Sie nur das Plugin-API-Interface des Hosts für den Erweiterungspunkt
kennen, zu dem Sie beitragen möchten; Sie implementieren oder referenzieren niemals selbst einen
pluggiat-Typ.

## Beispiel

Angenommen, der Host bietet einen Erweiterungspunkt für Exporter über ein Plugin-API-Interface
`Exporter` an:

```kotlin
interface Exporter {
    fun export(data: List<Row>): ByteArray
}
```

Ihr Plugin implementiert einfach dieses Interface:

```kotlin
class CsvExporter : Exporter {
    override fun export(data: List<Row>): ByteArray {
        // ...
    }
}
```

... und deklariert es unter dem Schlüssel des Erweiterungspunkts in seinem Manifest, zusammen mit
allen weiteren Feldern, die dieser konkrete Erweiterungspunkt erwartet (siehe die Dokumentation des
Hosts für den Schlüssel und seine Felder):

```yaml
extensions:
  exporters:
    - implementation: com.example.CsvExporter
      fileExtension: csv
      displayName: "CSV export"
```

`implementation` ist immer erforderlich und benennt den vollqualifizierten Klassennamen Ihrer
Implementierungsklasse. Alle anderen Felder hängen vom Erweiterungspunkt ab, zu dem Sie beitragen.

## Exklusive Erweiterungspunkte

Manche Erweiterungspunkte erlauben nur eine einzige aktive Implementierung über alle installierten
Plugins hinweg. Wenn Ihr Plugin und ein anderes installiertes Plugin beide zum selben exklusiven
Erweiterungspunkt-Schlüssel beitragen, werden **beide Plugins vollständig abgelehnt**, und es wird
eine Warnung protokolliert, die beide Plugin-IDs sowie den betroffenen Schlüssel nennt. Ob ein
gegebener Erweiterungspunkt exklusiv ist, legt der Host fest; konsultieren Sie dessen Dokumentation
für die von ihm angebotenen Erweiterungspunkte.
