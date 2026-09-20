# Plugin manifest

Every plugin ships a manifest file at `META-INF/plugin.yml` or `META-INF/plugin.yaml` inside its
JAR/ZIP. The manifest is validated against a JSON schema before it is mapped onto the plugin
metadata used by the host.

## Example

```yaml
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

## Fields

### Required

| Field        | Type   | Description                                                    |
|--------------|--------|------------------------------------------------------------------|
| `id`         | string | Unique id of the plugin                                          |
| `name`       | string | Human-readable display name                                      |
| `version`    | string | Version of the plugin, following the Maven version scheme        |
| `minVersion` | string | Minimum required host version, following the Maven version scheme |
| `icon`       | string | Base64-encoded icon; the format is auto-detected (any `ImageIO`-supported raster format, or SVG) |

### Optional

| Field                 | Type   | Description                                             |
|-----------------------|--------|----------------------------------------------------------|
| `description`         | string | Free-form description of the plugin                      |
| `author.name`         | string | Author display name (required if `author` is present)    |
| `author.mail`         | string | Author contact mail address                               |
| `links.documentation` | string | URL to the plugin's documentation                          |
| `links.sourceCode`    | string | URL to the plugin's source code repository                 |
| `legal.copyright`     | string | Free-form copyright notice                                 |
| `legal.license`       | string | License identifier; ideally an [SPDX identifier](https://spdx.org/licenses/), matched on a best-effort basis without rejecting unrecognised values |
| `dependencies[]`      | array  | Dependencies on other plugins, see below                   |
| `extensions.<key>[]`  | array  | Extension point contributions, see [Extension points](extension-points.md) |

### Dependencies

Each entry of `dependencies` declares a dependency on another plugin by id:

```yaml
dependencies:
  - id: com.example.other-plugin
    required: true
```

`required: true` means the depending plugin cannot load without the dependency; `required: false`
marks it as optional, allowing reduced functionality when the dependency is absent.

!!! note "Internal `$version` field"

    Manifests also carry an internal `$version` field used exclusively for migrating older manifest
    formats. It is not part of the fields listed above and is not exposed to plugin or host code.
