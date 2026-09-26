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

!!! tip "Security recommendation"

    `type = PluginLocationType.BUILTIN` is a trust signal only - the scanner does not itself verify
    that a location is actually free of third-party content. Only use it for a location your own
    build/installer fully controls; anything user-writable must be `EXTERNAL`, even if you expect
    only vetted plugins to end up there in practice.

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

Every `*.zip` file directly inside the location's directory is one plugin candidate. Its content is
scanned exactly like a `MultiJarWithOwnFolderScanStrategy` folder, but without ever unpacking the
ZIP onto disk - it is mounted as its own `java.nio.file.FileSystem` for the duration of the scan
and read from directly:

```text
plugins/
└── plugin-a.zip
    ├── plugin-a.jar       (contains META-INF/plugin.yml)
    └── plugin-a-lib.jar
```

The reported candidate's `path` is the `.zip` file itself, not a path inside the mounted
filesystem.

## Scanning

```kotlin
val scanner = PluginScanner(
    defaultSecurityChains = mapOf(
        PluginLocationType.BUILTIN to listOf(InsecureSecurityStrategy()),
        // see host-integration/security.md for a realistic EXTERNAL chain
    ),
)
val results = scanner.scan(listOf(location /* , ... your other locations */))

for (result in results) {
    when (result.status) {
        PluginScanStatus.LOADED -> println("Found plugin ${result.manifest?.id} at ${result.path}")
        else -> println("Invalid candidate at ${result.path}: ${result.errorMessage}")
    }
}
```

`PluginScanner.scan` returns one `PluginScanResult` per plugin candidate found across all given
locations, both valid (`status == LOADED`, with `manifest` set) and invalid ones (`errorMessage`
describing why; `manifest` is only `null` for `MANIFEST_NOT_FOUND`/`MANIFEST_INVALID` - a candidate
that failed the security chain keeps its manifest, so a host can still force-load it, see below).
Every location must resolve to a non-empty security chain - either its own `securityOverride` or a
`defaultSecurityChains` entry for its `type` - or scanning throws a configuration error; see
[Security](security.md) for details.

## Orchestration via `PluginManager`

`PluginScanner` on its own only scans - it does not resolve plugin id collisions across locations,
enforce `minVersion`, create class loaders or activate extensions. For the full, host-facing
orchestration flow (`scan()`/`reload()`/`unload()`/`forceLoad()`, typed extension access via
`getExtensions<T>`/`getFirstExtension<T>`, and the nested builder blocks shown above), configure a
[`PluginManager`](plugin-manager.md) instead of using `PluginScanner` directly - it wires a
`PluginScanner` internally and builds on top of it.
