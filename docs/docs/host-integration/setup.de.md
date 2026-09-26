# Plugin-Verzeichnisse konfigurieren

Als Host-Anwendung konfigurieren Sie ein oder mehrere `PluginLocation`s und übergeben sie einem
`PluginScanner`, um Plugin-Kandidaten auf der Festplatte zu entdecken.

## Ein Verzeichnis definieren

```kotlin
val location = PluginLocation(
    path = Paths.get("/opt/myapp/plugins"),
    type = PluginLocationType.EXTERNAL,
    scanStrategy = ZipJarScanStrategy(), // default if omitted
)
```

* `path` - Verzeichnis, das nach Plugin-Kandidaten durchsucht wird.
* `type` - `BUILTIN` für mit der Host-Anwendung ausgelieferte Verzeichnisse, `EXTERNAL` für von
  Dritten beigetragene Verzeichnisse (z. B. den Plugin-Ordner eines Benutzers).
* `scanStrategy` - welcher der drei unten stehenden Lademodi für dieses Verzeichnis gilt; Standard
  ist `ZipJarScanStrategy`.

!!! tip "Sicherheitsempfehlung"

    `type = PluginLocationType.BUILTIN` ist nur ein Vertrauenssignal - der Scanner prüft selbst
    nicht, ob ein Verzeichnis tatsächlich frei von Drittinhalten ist. Nur für ein Verzeichnis
    verwenden, das der eigene Build bzw. Installer vollständig kontrolliert; alles vom Benutzer
    Beschreibbare muss `EXTERNAL` sein, auch wenn dort in der Praxis nur geprüfte Plugins erwartet
    werden.

## Lademodi

Der Lademodus eines Verzeichnisses wird direkt durch die als `scanStrategy` übergebene
`PluginScanStrategy`-Implementierung ausgedrückt - es gibt keine separate Lademodus-Einstellung.

### `SingleJarScanStrategy`

Jede `*.jar`-Datei direkt im Verzeichnis ist ihr eigener Plugin-Kandidat, wobei das Manifest aus dem
eigenen `META-INF` dieser JAR gelesen wird:

```text
plugins/
├── plugin-a.jar   (enthält META-INF/plugin.yml)
└── plugin-b.jar   (enthält META-INF/plugin.yml)
```

### `MultiJarWithOwnFolderScanStrategy`

Jeder Unterordner des Verzeichnisses ist ein Plugin-Kandidat, bestehend aus allen `*.jar`-Dateien
direkt darin. Die Manifest-JAR unter ihnen wird durch das Vorhandensein eines Manifest-Eintrags
identifiziert:

```text
plugins/
└── plugin-a/
    ├── plugin-a.jar       (enthält META-INF/plugin.yml)
    └── plugin-a-lib.jar
```

### `ZipJarScanStrategy` (Standard)

Jede `*.zip`-Datei direkt im Verzeichnis ist ein Plugin-Kandidat. Ihr Inhalt wird genau wie ein
`MultiJarWithOwnFolderScanStrategy`-Ordner gescannt, jedoch ohne die ZIP jemals auf die Festplatte
zu entpacken - sie wird für die Dauer des Scans als eigenes `java.nio.file.FileSystem` eingehängt
und direkt daraus gelesen:

```text
plugins/
└── plugin-a.zip
    ├── plugin-a.jar       (enthält META-INF/plugin.yml)
    └── plugin-a-lib.jar
```

Der `path` des gemeldeten Kandidaten ist die `.zip`-Datei selbst, nicht ein Pfad innerhalb des
eingehängten Dateisystems.

## Scannen

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

`PluginScanner.scan` liefert einen `PluginScanResult` pro über alle angegebenen Verzeichnisse
gefundenem Plugin-Kandidaten, sowohl gültige (`status == LOADED`, mit gesetztem `manifest`) als auch
ungültige (`errorMessage` beschreibt den Grund; `manifest` ist nur bei `MANIFEST_NOT_FOUND`/
`MANIFEST_INVALID` `null` - ein an der Sicherheitskette gescheiterter Kandidat behält sein
Manifest, damit ein Host ihn weiterhin per Force-Load laden kann, siehe unten). Jedes Verzeichnis
muss auf eine nicht leere Sicherheitskette auflösen - entweder seinen eigenen `securityOverride`
oder einen `defaultSecurityChains`-Eintrag für seinen `type` - andernfalls wirft das Scannen einen
Konfigurationsfehler; siehe [Sicherheit](security.de.md) für Details.

## Orchestrierung über `PluginManager`

`PluginScanner` scannt für sich genommen nur - er löst keine Plugin-ID-Kollisionen über Verzeichnisse
hinweg auf, erzwingt kein `minVersion`, erstellt keine Classloader und aktiviert keine
Erweiterungen. Für den vollständigen, host-seitigen Orchestrierungsablauf
(`scan()`/`reload()`/`unload()`/`forceLoad()`, typisierten Erweiterungszugriff über
`getExtensions<T>`/`getFirstExtension<T>` und die oben gezeigten verschachtelten Builder-Blöcke)
konfigurieren Sie stattdessen einen [`PluginManager`](plugin-manager.de.md), statt `PluginScanner`
direkt zu verwenden - er verdrahtet intern einen `PluginScanner` und baut darauf auf.
