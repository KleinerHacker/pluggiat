# Plugin-Manifest

Jedes Plugin liefert eine Manifestdatei unter `META-INF/plugin.yml` oder `META-INF/plugin.yaml`
innerhalb seiner JAR/ZIP mit. Das Manifest wird gegen ein JSON-Schema validiert, bevor es auf die
vom Host verwendeten Plugin-Metadaten abgebildet wird.

## Beispiel

```yaml
id: com.example.sample-plugin
name: Sample Plugin
version: "1.2.3"
minVersion: "1.0.0"
icon: <base64-kodiertes PNG/JPEG/SVG/...>
description: Ein Beispiel-Plugin.
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

| Feld         | Typ    | Beschreibung                                                      |
|--------------|--------|---------------------------------------------------------------------|
| `id`         | string | Eindeutige ID des Plugins                                            |
| `name`       | string | Menschenlesbarer Anzeigename                                         |
| `version`    | string | Version des Plugins, nach dem Maven-Versionsschema                   |
| `minVersion` | string | Minimal erforderliche Host-Version, nach dem Maven-Versionsschema    |
| `icon`       | string | Base64-kodiertes Icon; das Format wird automatisch erkannt (jedes von `ImageIO` unterstützte Rasterformat oder SVG) |

### Optionale Felder

| Feld                  | Typ    | Beschreibung                                              |
|-----------------------|--------|--------------------------------------------------------------|
| `description`         | string | Freitext-Beschreibung des Plugins                             |
| `author.name`         | string | Anzeigename des Autors (Pflicht, sofern `author` angegeben ist) |
| `author.mail`         | string | Kontakt-E-Mail-Adresse des Autors                              |
| `links.documentation` | string | URL zur Dokumentation des Plugins                              |
| `links.sourceCode`    | string | URL zum Quellcode-Repository des Plugins                       |
| `legal.copyright`     | string | Freitext-Copyright-Hinweis                                     |
| `legal.license`       | string | Lizenzkennung; idealerweise eine [SPDX-Kennung](https://spdx.org/licenses/), die nach bestem Bemühen abgeglichen wird, ohne unbekannte Werte abzulehnen |
| `dependencies[]`      | array  | Abhängigkeiten zu anderen Plugins, siehe unten                 |
| `extensions.<key>[]`  | array  | Beiträge zu Erweiterungspunkten, siehe [Erweiterungspunkte](extension-points.de.md) |

### Abhängigkeiten

Jeder Eintrag von `dependencies` deklariert eine Abhängigkeit zu einem anderen Plugin anhand seiner ID:

```yaml
dependencies:
  - id: com.example.other-plugin
    required: true
```

`required: true` bedeutet, dass das abhängige Plugin ohne diese Abhängigkeit nicht geladen werden
kann; `required: false` markiert sie als optional und erlaubt eingeschränkte Funktionalität, wenn
die Abhängigkeit fehlt.

!!! note "Internes Feld `$version`"

    Manifeste tragen zudem ein internes Feld `$version`, das ausschließlich zur Migration älterer
    Manifestformate verwendet wird. Es ist nicht Teil der oben aufgeführten Felder und wird weder
    Plugin- noch Host-Code zugänglich gemacht.
