# Extension points

A plugin contributes functionality to the host application through extension points. Each
extension point is identified by a key (declared under `extensions.<key>[]` in the manifest, see
[Plugin manifest](manifest.md)) and defined by the host - not by the plugin.

As a plugin developer you only need to know the host's plugin API interface for the extension
point you want to contribute to; you never implement or reference any pluggiat type yourself.

## Example

Assume the host offers an extension point for exporters through a plugin API interface `Exporter`:

```kotlin
interface Exporter {
    fun export(data: List<Row>): ByteArray
}
```

Your plugin simply implements that interface:

```kotlin
class CsvExporter : Exporter {
    override fun export(data: List<Row>): ByteArray {
        // ...
    }
}
```

...and declares it under the extension point's key in its manifest, together with whatever extra
fields that particular extension point expects (see the host's documentation for the key and its
fields):

```yaml
extensions:
  exporters:
    - implementation: com.example.CsvExporter
      fileExtension: csv
      displayName: "CSV export"
```

`implementation` is always required and names the fully qualified class name of your
implementation class. All other fields depend on the extension point you are contributing to.

## Exclusive extension points

Some extension points only allow a single active implementation across all installed plugins. If
your plugin and another installed plugin both contribute to the same exclusive extension point
key, **both plugins are rejected entirely** and a warning naming both plugin ids and the affected
key is logged. Whether a given extension point is exclusive is defined by the host; consult the
host's documentation for the extension points it offers.
