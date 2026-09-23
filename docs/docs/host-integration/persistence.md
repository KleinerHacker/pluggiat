# Persistence

`PluginPersistenceStrategy` is the single, generic key-value persistence mechanism the whole
framework uses for anything that needs to survive a host restart - the checksum security strategy's
accepted checksums, and every plugin's enabled/disabled status:

```kotlin
interface PluginPersistenceStrategy {
    fun read(pluginId: String, key: String): String?
    fun write(pluginId: String, key: String, value: String)
}
```

Exactly one instance is configured for the whole framework, via `PluginManagerConfiguration.persistenceStrategy`
(see [PluginManager](plugin-manager.md)).

## Shipped implementations

### `NoPersistenceStrategy` (default)

`read` always returns `null`, `write` is a no-op. **Not recommended for production software** - any
state relying on persistence (accepted checksums, enabled/disabled status) resets on every host
restart. Logs a `WARN` once on first use.

### `CustomPersistenceStrategy`

Delegates to host-provided function interfaces, for hosts that already have their own storage:

```kotlin
val strategy = CustomPersistenceStrategy(
    readCallback = { pluginId, key -> myStorage.read(pluginId, key) },
    writeCallback = { pluginId, key, value -> myStorage.write(pluginId, key, value) },
)
```

### `FilePersistenceStrategy`

Backed by a single file, in one of four formats (`PersistenceFileFormat.PROPERTIES` (default),
`JSON`, `YAML`, `XML`) - no new dependency is required for any of them:

```kotlin
val strategy = FilePersistenceStrategy(Paths.get("/var/lib/myapp/plugin-state.properties"))
```

The whole file is read into memory on first access and rewritten in full on every `write` -
appropriate for the small amount of per-plugin state the framework itself stores, not for large or
high-frequency data.

### `DatabasePersistenceStrategy`

Backed by a JDBC `javax.sql.DataSource`, plain JDBC only (no ORM). Creates its own table
`plugin_state(plugin_id, plugin_key, plugin_value)` automatically on first use if it does not exist
yet (column names deliberately avoid the reserved SQL words `key`/`value`):

```kotlin
val strategy = DatabasePersistenceStrategy(myDataSource)
```

### `ObjectPersistenceStrategy`

Delegates every read/write to a pair of plain host-provided functions, without prescribing how or
where the value is actually stored - e.g. against a host's own settings object, an in-memory map, or
any other backing store the host already owns:

```kotlin
val strategy = ObjectPersistenceStrategy(
    getter = { pluginId, key -> myHostSettings.read(pluginId, key) },
    setter = { pluginId, key, value -> myHostSettings.write(pluginId, key, value) },
)
```

Unlike `CustomPersistenceStrategy`, which delegates to host-provided function interfaces,
`ObjectPersistenceStrategy` takes plain Kotlin lambdas - useful when the host already has a small,
inline read/write pair rather than a dedicated function interface implementation.

## Writing your own

Any class implementing `PluginPersistenceStrategy` works, without framework changes - e.g. to back
it with a cloud key-value store, a cache layer, or an existing host configuration system.
