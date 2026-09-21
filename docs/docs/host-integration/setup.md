# Configuring plugin locations

As a host application, you configure one or more `PluginLocation`s and hand them to a
`PluginScanner` to discover plugin candidates on disk.

## Defining a location

```kotlin
val location = PluginLocation(
    path = Paths.get("/opt/myapp/plugins"),
    type = PluginLocationType.EXTERNAL,
    scanStrategy = ZipJarScanStrategy(), // default if omitted
)
```

* `path` - directory to scan for plugin candidates.
* `type` - `BUILTIN` for locations shipped with the host application, `EXTERNAL` for
  locations contributed by someone else (e.g. a user's plugin folder).
* `scanStrategy` - which of the three load modes below applies to this location; defaults to
  `ZipJarScanStrategy`.

## Load modes

The load mode of a location is expressed directly by the `PluginScanStrategy` implementation
passed as `scanStrategy` - there is no separate load-mode setting.

### `SingleJarScanStrategy`

Every `*.jar` file directly inside the location's directory is its own plugin candidate, with the
manifest read from that JAR's own `META-INF`:

```text
plugins/
├── plugin-a.jar   (contains META-INF/plugin.yml)
└── plugin-b.jar   (contains META-INF/plugin.yml)
```

### `MultiJarWithOwnFolderScanStrategy`

Every subfolder of the location's directory is one plugin candidate, made up of all `*.jar` files
directly inside it. The manifest JAR among them is identified by the presence of a manifest entry:

```text
plugins/
└── plugin-a/
    ├── plugin-a.jar       (contains META-INF/plugin.yml)
    └── plugin-a-lib.jar
```

### `ZipJarScanStrategy` (default)

Every `*.zip` file directly inside the location's directory is one plugin candidate. It is
unpacked into a temporary directory and then scanned exactly like a
`MultiJarWithOwnFolderScanStrategy` folder:

```text
plugins/
└── plugin-a.zip
    ├── plugin-a.jar       (contains META-INF/plugin.yml)
    └── plugin-a-lib.jar
```

The temporary directory and everything extracted into it are registered for cleanup via
`File.deleteOnExit()`; no manual cleanup is required, though the actual deletion only happens once
the JVM shuts down.

## Scanning

```kotlin
val results = PluginScanner().scan(listOf(location /* , ... your other locations */))

for (result in results) {
    when (result.status) {
        PluginScanStatus.LOADED -> println("Found plugin ${result.manifest?.id} at ${result.path}")
        else -> println("Invalid candidate at ${result.path}: ${result.errorMessage}")
    }
}
```

`PluginScanner.scan` returns one `PluginScanResult` per plugin candidate found across all given
locations, both valid (`status == LOADED`, with `manifest` set) and invalid ones (`manifest ==
null`, with `errorMessage` describing why).
