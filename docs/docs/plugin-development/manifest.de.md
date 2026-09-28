# Plugin-Manifest

Jedes Plugin liefert eine Manifestdatei unter `META-INF/plugin.yml` oder `META-INF/plugin.yaml`
innerhalb seiner JAR/ZIP mit. Das Manifest wird gegen ein JSON-Schema validiert, bevor es auf die
vom Host verwendeten Plugin-Metadaten abgebildet wird.

## Beispiel

```yaml
$version: 1
id: com.example.sample-plugin
name: Sample Plugin
version: "1.2.3"
minVersion: "1.0.0"
icon: <base64-encoded PNG/JPEG/SVG/...>
description: A sample plugin.
author:
  name: Jane Doe
  mail: jane.doe@example.com
links:
  documentation: https://example.com/docs
  sourceCode: https://example.com/source
legal:
  copyright: "Copyright (c) 2026 Jane Doe"
  license: Apache-2.0
dependencies:
  - id: com.example.other-plugin
    required: true
  - id: com.example.optional-plugin
    required: false
extensions:
  com.example.commands:
    - implementation: com.example.sample.SampleCommand
```

## Felder

### Pflichtfelder

| Feld         | Typ     | Beschreibung                                                      |
|--------------|---------|---------------------------------------------------------------------|
| `$version`   | integer | Interne Revision des Manifest-Schemas, eine Ganzzahl von mindestens `0` (die Beispiele verwenden `1`); ein Manifest ohne dieses Feld wird abgelehnt, siehe Hinweis unten |
| `id`         | string  | Eindeutige ID des Plugins; nur Buchstaben, Ziffern, `.`, `_` und `-`, beginnend und endend mit einem Buchstaben oder einer Ziffer, höchstens 128 Zeichen |
| `name`       | string  | Menschenlesbarer Anzeigename                                         |
| `version`    | string  | Version des Plugins, nach dem Maven-Versionsschema                   |
| `minVersion` | string  | Minimal erforderliche Host-Version, nach dem Maven-Versionsschema; wird nur geprüft, wenn der Host eine eigene `hostVersion` konfiguriert hat, andernfalls entfällt die Prüfung |
| `icon`       | string  | Base64-kodiertes Icon; das Format wird nach bestem Bemühen erkannt (jedes von `ImageIO` unterstützte Rasterformat oder SVG). Ein unbekanntes Format oder ungültiger Base64-Inhalt wird nur als Warnung protokolliert und führt nie zur Ablehnung des Plugins |

### Optionale Felder

| Feld                  | Typ    | Beschreibung                                              |
|-----------------------|--------|--------------------------------------------------------------|
| `description`         | string | Freitext-Beschreibung des Plugins                             |
| `author.name`         | string | Anzeigename des Autors (Pflicht, sofern `author` angegeben ist) |
| `author.mail`         | string | Kontakt-E-Mail-Adresse des Autors                              |
| `links.documentation` | string | URL zur Dokumentation des Plugins                              |
| `links.sourceCode`    | string | URL zum Quellcode-Repository des Plugins                       |
| `legal.copyright`     | string | Freitext-Copyright-Hinweis                                     |
| `legal.license`       | string | Lizenzkennung; idealerweise eine [SPDX-Kennung](https://spdx.org/licenses/), nach bestem Bemühen abgeglichen: ein SPDX-Ausdruck (`AND`, `OR`, `WITH`, Klammern, ein abschließendes `+`) wird in seine einzelnen Kennungen zerlegt, und ein unbekannter Wert wird nur als Warnung protokolliert, er führt nie zur Ablehnung des Plugins |
| `dependencies[]`      | array  | Abhängigkeiten zu anderen Plugins, siehe unten                 |
| `extensions.<key>[]`  | array  | Beiträge zu Erweiterungspunkten, siehe [Erweiterungspunkte](extension-points.de.md) |

### Unbekannte Felder

Das Manifest-Schema toleriert keine unbekannten Felder: ein oben nicht aufgeführtes Feld wird
sowohl auf oberster Ebene als auch innerhalb von `author`, `links`, `legal` und jedem Eintrag von
`dependencies[]` abgelehnt, und das Plugin wird als Plugin mit ungültigem Manifest gemeldet. Nur
die Einträge unter `extensions.<key>[]` dürfen neben `implementation` zusätzliche,
erweiterungspunktspezifische Felder tragen.

### Abhängigkeiten

Jeder Eintrag von `dependencies` deklariert eine Abhängigkeit zu einem anderen Plugin anhand seiner
ID. Sowohl `id` als auch `required` sind für jeden Eintrag Pflicht, und `id` folgt denselben
Formatregeln wie die eigene `id` eines Plugins:

```yaml
dependencies:
  - id: com.example.other-plugin
    required: true
```

`required: true` bedeutet, dass das abhängige Plugin ohne diese Abhängigkeit nicht geladen werden
kann; `required: false` markiert sie als optional und erlaubt eingeschränkte Funktionalität, wenn
die Abhängigkeit fehlt.

!!! note "Internes Feld `$version`"

    Jedes Manifest muss zudem ein internes Feld `$version` (eine Ganzzahl) tragen, das
    ausschließlich zur Migration älterer Manifestformate verwendet wird. Es ist oben unter den
    Pflichtfeldern aufgeführt, weil ein Manifest ohne dieses Feld abgelehnt wird, sein Wert wird
    jedoch weder Plugin- noch Host-Code zugänglich gemacht.
