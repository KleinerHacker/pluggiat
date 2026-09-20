# Defining extension points

As a host application, you define the extension points your plugins can contribute to. A plugin
developer only ever sees your plugin API interface - never any pluggiat type.

## Defining an extension point

1. Define a plugin API interface plugins implement against, e.g. `Exporter`.
2. Define a configuration class implementing `ExtensionConfiguration<T>`, annotated with
   `@ExtensionPoint`:

```kotlin
interface Exporter {
    fun export(data: List<Row>): ByteArray
}

@ExtensionPoint(key = "exporters", exclusive = false)
data class ExporterConfig(
    override val implementation: KClass<out Exporter>,
    val fileExtension: String,
    val displayName: String,
) : ExtensionConfiguration<Exporter>
```

`key` is the `extensions.<key>[]` key plugins declare their contribution under. Every additional
constructor property of the configuration class (besides `implementation`) is mapped from the
matching field of the plugin manifest's extension entry.

Set `exclusive = true` if at most one plugin may ever populate this extension point; if two
plugins do, both are rejected entirely (see [Extension points](../plugin-development/extension-points.md)
for the plugin developer's perspective).

## Registering extension points

Register all your `ExtensionConfiguration` classes with an `ExtensionPointRegistry` once, at host
startup:

```kotlin
val registry = ExtensionPointRegistry(
    listOf(ExporterConfig::class /* , ... your other extension points */)
)
```

The registry validates that every registered class carries an `@ExtensionPoint` annotation and
that no key is registered twice.

## Resolving extensions across all plugins

Pass the registry to an `ExtensionAggregator` together with the scanned plugin candidates
(id, on-disk `Path`, and parsed manifest) to resolve, instantiate and aggregate their extensions:

```kotlin
val aggregator = ExtensionAggregator(registry)
val result = aggregator.aggregate(candidates)

val exporters = result.extensionsByKey["exporters"] ?: emptyList()
for (extension in exporters) {
    val exporter = extension.instance as Exporter
    val config = extension.configuration as ExporterConfig
    // ...
}
```

`result.pluginResults` reports, per plugin, its id, its `Path` (passed through unchanged from the
candidate) and its status (`LOADED` or `REJECTED_EXCLUSIVE_CONFLICT`).
