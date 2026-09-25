# PluginManager

`PluginManager` ist der zentrale, host-weite Einstiegspunkt des Frameworks. Er wird über eine
Kotlin-Builder-DSL erstellt:

```kotlin
val manager = pluginManager {
    location {
        path = Paths.get("/opt/myapp/plugins")
        type = PluginLocationType.EXTERNAL
        securityOverride {
            addStrategy(SignatureSecurityStrategy(publicKeyProvider))
        }
    }
    defaultSecurityChain {
        type = PluginLocationType.EXTERNAL
        addStrategy(InsecureSecurityStrategy())
    }
    dependencyStrategy = UnrestrictedPluginDependencyStrategy()
    persistenceStrategy = FilePersistenceStrategy(Paths.get("/var/lib/myapp/plugin-state.properties"))
    exceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    sdkWhitelistEntry {
        packageName = "com.myapp.sdk"
    }
    extensionPoint(ExporterExtensionConfig::class)
    hostVersion = "2.3.0"
}
```

## Was er konfiguriert

`PluginManagerConfiguration` (der Empfänger des Builders) bündelt jede host-weite Einstellung, die
das Framework benötigt, an einer Stelle, jeweils über einen verschachtelten Builder-Block befüllt:

* `pluginLocations` - die zu scannenden Verzeichnisse, über `location { path = ...; type = ...; ... }`;
  die eigene Sicherheits-Override-Kette eines Verzeichnisses wird über einen verschachtelten
  `securityOverride { addStrategy(...) }`-Block gesetzt.
* `defaultSecurityChains` - Standard-Sicherheitskette pro `PluginLocationType`, über
  `defaultSecurityChain { type = ...; addStrategy(...) }`, siehe [Sicherheit](security.md).
* `dependencyStrategy` - die globale `PluginDependencyStrategy`.
* `persistenceStrategy` - die einzelne `PluginPersistenceStrategy`-Instanz, siehe
  [Persistenz](persistence.md).
* `exceptionHandlingStrategy` - die einzelne, host-weite `ExceptionHandlingStrategy`, siehe
  [Plugin-Lifecycle-Verwaltung](plugin-lifecycle-management.md).
* `sdkWhitelist` - Pakete Ihres eigenen SDK, die Plugins zugänglich gemacht werden, über
  `sdkWhitelistEntry { packageName = ...; recursive = ... }`.
* `hostVersion` - die Version Ihrer eigenen Anwendung, abgeglichen gegen das `minVersion` eines
  Plugins.
* `extensionPointClasses` - Ihre `ExtensionConfiguration`-Klassen, über
  `extensionPoint(MyConfig::class)`.

## Was er bereitstellt

```kotlin
manager.scanner  // PluginScanner, vorverdrahtet mit defaultSecurityChains
manager.security // PluginSecurity, ebenfalls zur Vorverdrahtung des scanners verwendet
manager.loader   // PluginLoader, vorverdrahtet mit sdkWhitelist
manager.registry // ExtensionPointRegistry, einmalig aus extensionPointClasses erstellt
```

Diese vorverdrahteten Instanzen sind nur eine Annehmlichkeit - die zugrunde liegenden
`PluginScanner`/`PluginLoader`-Konstruktorparameter bleiben direkt nutzbar, falls Sie lieber selbst
verdrahten möchten.

## Zustand

`PluginManager` ist zustandsbehaftet und intern synchronisiert - er hält den aktuellen
Orchestrierungszustand selbst, statt bei jedem Aufruf ein kombiniertes Ergebnisobjekt
zurückzugeben, sodass jeder Teil Ihres Hosts denselben aktuellen Zustand direkt von der Instanz
lesen kann:

Alle öffentlichen Methoden sind intern synchronisiert und können von jedem Thread aus sicher
aufgerufen werden. Ein Aufruf blockiert, bis ein anderer Aufruf auf derselben Instanz abgeschlossen
ist - z. B. sieht `getExtensions`, das `extensionsByKey` während eines gleichzeitigen `scan()`
liest, entweder den Zustand von vor oder von nach diesem `scan()`, niemals einen teilweise
aktualisierten.

* `scanResults: List<PluginScanResult>` - jeder vom letzten `scan()` gefundene Plugin-Kandidat, mit
  seinem endgültigen Status (einschließlich der Orchestrierungsebenen-Status unten).
* `loadedPlugins: Map<String, LoadedPlugin>` - aktuell geladene Plugins, nach Plugin-ID indiziert.
* `extensionsByKey: Map<String, List<ResolvedExtension>>` - aktuell aktive Erweiterungen, gruppiert
  nach Erweiterungspunkt-Schlüssel; verwenden Sie `getExtensions<T>(key)`/`getFirstExtension<T>(key)`
  für typisierten Zugriff, statt dies direkt zu lesen.

## Orchestrierung: scan, reload, unload, force-load

```kotlin
// 1. Jedes konfigurierte Verzeichnis scannen, ID-Kollisionen und minVersion auflösen, alles laden
//    und aktivieren, was besteht.
manager.scan()

// 2. scanResults auf alles prüfen, was nicht geladen wurde.
val failed = manager.scanResults.filter { it.status != PluginScanStatus.LOADED }

// 3. Regulärer Reload, nachdem ein vorübergehendes Problem behoben wurde (prüft die Sicherheit zuerst erneut).
manager.reload(pluginId)

// Bewusste Deaktivierung (das Gegenstück zu reload).
manager.unload(pluginId)

// Einen Kandidaten trotz fehlgeschlagener Sicherheitsprüfung per Force-Load laden.
manager.forceLoad(pluginId)

// 4. Typisierter Zugriff auf geladene Implementierungen.
val exporters: List<ExporterExtension> = manager.getExtensions("exporters")
val renderer: RendererExtension? = manager.getFirstExtension("renderer") // exklusiver Erweiterungspunkt
```

`scan()` löst Plugin-ID-Kollisionen (siehe unten) und `minVersion`-Verstöße auf, bevor überhaupt ein
Classloader erstellt wird, lädt dann jeden verbleibenden Kandidaten in Abhängigkeitsreihenfolge und
aktiviert seine Erweiterungen - alles in einem einzigen Aufruf. Ein zuvor geladenes Plugin, das im
neuen Ergebnis nicht mehr vorhanden ist, wird automatisch geschlossen.

`reload(pluginId)` ist der reguläre, sichere Weg, ein einzelnes Plugin (erneut) zu aktivieren: Es
prüft zuerst die Sicherheitskette erneut (über denselben Ablauf, den bereits `reactivate` verwendet,
siehe unten) und ersetzt den Eintrag des Plugins in `loadedPlugins` nur bei Erfolg.

`unload(pluginId)` ist das Gegenstück zu `reload`: eine bewusste, host-initiierte Deaktivierung. Es
ruft `onDisable`/`onUnload` auf den echten Erweiterungsinstanzen des Plugins auf, persistiert es als
deaktiviert (Grund `ExtensionAggregator.USER_REASON`), schließt seinen Classloader und entfernt es
aus `loadedPlugins`/`extensionsByKey`.

### Force-Load

```kotlin
val result = manager.forceLoad(pluginId)
```

Lädt den Kandidaten bedingungslos, unter vollständiger Umgehung seines aktuellen
`scanResults`-Status - die Entscheidung, *ob* dies gerechtfertigt ist (z. B. nach Rückfrage beim
Benutzer des Hosts), liegt vollständig beim Aufrufer. Der `scanResults`-Eintrag selbst bleibt
unverändert; nur `loadedPlugins`/`extensionsByKey` zeigen das Plugin danach als geladen an.

Für sich genommen ist `forceLoad` eine einmalige Übersteuerung: Ein späteres `reload`/`scan` prüft
die Sicherheitskette von Grund auf erneut und schlägt aus demselben Grund erneut fehl. Es gibt zwei
Wege, eine Übersteuerung dauerhaft zu machen:

* Für eine [`PersistableSecurityStrategy`](security.md) wie die Checksum-Strategie rufen Sie
  danach `manager.write<ChecksumSecurityStrategy>(pluginId)` auf - die Strategie persistiert ihren
  eigenen akzeptierten Zustand (z. B. die tatsächliche Prüfsumme des Kandidaten als neue erwartete
  Prüfsumme), sodass die nächste reguläre Prüfung erfolgreich verläuft.
* Für jede andere Strategie (z. B. eine Signaturstrategie ohne persistierbaren Zustand) übergeben
  Sie stattdessen `forceLoad(pluginId, persistException = true)`. Dies persistiert eine generische,
  dauerhafte Sicherheitsausnahme für die Plugin-ID; jede zukünftige Sicherheitsprüfung für sie wird
  vollständig übersprungen (jedes Mal als WARN protokolliert), bis der Host die Ausnahme selbst
  aufhebt.

### Reaktivierung (`reactivate`)

```kotlin
val result = manager.reactivate(location, path, manifest, dependencies)
```

Der grundlegendere Baustein, auf dem `reload` aufbaut: prüft zuerst die Sicherheitskette gegen den
Kandidaten unter `path` erneut, über `manager.security`, und lädt ihn nur bei Erfolg über
`manager.loader` neu - persistiert ihn dabei über
`PluginManagerConfiguration.persistenceStrategy` wieder als aktiviert. Schlägt die erneute Prüfung
fehl, bleibt das Plugin deaktiviert (sein Deaktivierungsgrund wird aktualisiert), und es findet kein
automatisches Force-Load statt. Siehe [Plugin-Lifecycle-Verwaltung](plugin-lifecycle-management.md)
für das Gesamtbild.

## ID-Kollisionen und minVersion

Tragen zwei Verzeichnisse einen Kandidaten mit derselben Plugin-ID bei, wird dies rein anhand der
Version aufgelöst (Maven-Versionsschema), und zwar nur unter den Kandidaten, die ihre
Sicherheitskette bereits erfolgreich durchlaufen haben (`LOADED`): Die strikt höhere Version
gewinnt, der Rest wird in `scanResults` als `ID_COLLISION` markiert. Ist die höchste Version
zwischen zwei oder mehr davon gleich, wird die gesamte Gruppe sofort abgelehnt (`ID_COLLISION`, als
Sicherheitswarnung protokolliert) - es gibt keine weitere Konfliktauflösung. Ein Kandidat, dessen
Sicherheitskette fehlgeschlagen ist (z. B. `SECURITY_PROBLEM`), konkurriert nie um die Version und
kann einen `LOADED`-Kandidaten derselben ID nicht verdrängen, unabhängig davon, welche Version er
deklariert.

Ein Kandidat, dessen Manifest ein `minVersion` deklariert, das neuer als die konfigurierte
`hostVersion` ist, wird als `MIN_VERSION_VIOLATION` markiert und nie geladen. `hostVersion = null`
(der Standard) überspringt diese Prüfung vollständig.

Siehe [Fehlersuche](troubleshooting.md) für die vollständige Liste der `PluginScanStatus`-Werte und
ihre Bedeutung.
