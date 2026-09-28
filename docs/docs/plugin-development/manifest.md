# Plugin manifest

Every plugin ships a manifest file at `META-INF/plugin.yml` or `META-INF/plugin.yaml` inside its
JAR/ZIP. The manifest is validated against a JSON schema before it is mapped onto the plugin
metadata used by the host.

## Example

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

## Fields

### Required

| Field        | Type    | Description                                                    |
|--------------|---------|------------------------------------------------------------------|
| `$version`   | integer | Internal manifest schema revision, an integer of at least `0` (the examples use `1`); a manifest without it is rejected, see the note below |
| `id`         | string  | Unique id of the plugin; only letters, digits, `.`, `_` and `-`, starting and ending with a letter or digit, at most 128 characters |
| `name`       | string  | Human-readable display name                                      |
| `version`    | string  | Version of the plugin, following the Maven version scheme        |
| `minVersion` | string  | Minimum required host version, following the Maven version scheme; only enforced if the host has configured its own `hostVersion`, otherwise the check is skipped |
| `icon`       | string  | Base64-encoded icon; the format is detected on a best-effort basis (any `ImageIO`-supported raster format, or SVG). An unrecognised format or invalid Base64 content is only logged as a warning and never rejects the plugin |

### Optional

| Field                 | Type   | Description                                             |
|-----------------------|--------|----------------------------------------------------------|
| `description`         | string | Free-form description of the plugin                      |
| `author.name`         | string | Author display name (required if `author` is present)    |
| `author.mail`         | string | Author contact mail address                               |
| `links.documentation` | string | URL to the plugin's documentation                          |
| `links.sourceCode`    | string | URL to the plugin's source code repository                 |
| `legal.copyright`     | string | Free-form copyright notice                                 |
| `legal.license`       | string | License identifier; ideally an [SPDX identifier](https://spdx.org/licenses/), matched on a best-effort basis: an SPDX expression (`AND`, `OR`, `WITH`, parentheses, a trailing `+`) is split into its individual identifiers, and an unrecognised value is only logged as a warning, it never rejects the plugin |
| `dependencies[]`      | array  | Dependencies on other plugins, see below                   |
| `extensions.<key>[]`  | array  | Extension point contributions, see [Extension points](extension-points.md) |

### Unknown fields

The manifest schema does not tolerate unknown fields: a field that is not listed above is rejected
at the top level as well as inside `author`, `links`, `legal` and every `dependencies[]` entry, and
the plugin is reported as having an invalid manifest. Only the entries under `extensions.<key>[]`
may carry additional, extension-point-specific fields next to `implementation`.

### Dependencies

Each entry of `dependencies` declares a dependency on another plugin by id. Both `id` and
`required` are mandatory for every entry, and `id` follows the same format rules as a plugin's own
`id`:

```yaml
dependencies:
  - id: com.example.other-plugin
    required: true
```

`required: true` means the depending plugin cannot load without the dependency; `required: false`
marks it as optional, allowing reduced functionality when the dependency is absent.

!!! note "Internal `$version` field"

    Every manifest must also carry an internal `$version` field (an integer), used exclusively for
    migrating older manifest formats. It is listed among the required fields above because a
    manifest without it is rejected, but its value is not exposed to plugin or host code.
