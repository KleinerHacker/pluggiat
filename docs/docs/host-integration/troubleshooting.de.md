# Fehlersuche

pluggiat protokolliert über SLF4J (`org.slf4j`); binden Sie jede Anbindung ein, die Ihre Anwendung
bereits verwendet. Diese Seite listet die Log-Level-Konvention des gesamten Frameworks sowie die
Fehler-/Konfliktfälle auf, die Ihnen am wahrscheinlichsten begegnen.

## Übersicht der Log-Level

* **`INFO`** - Scan-Start pro Verzeichnis (Pfad des Verzeichnisses, Scan-Strategie und
  Verzeichnistyp - die Sicherheitskette ist nur auf `TRACE` sichtbar), ein erfolgreich geladenes
  Plugin, eine dauerhafte, über `PluginPersistenceStrategy` persistierte
  Aktiviert/Deaktiviert-Änderung (`reload`, `unload`).
* **`WARN`** - ein ungültiges Manifest, eine fehlgeschlagene Sicherheitsstrategie/-kette
  (`SECURITY_PROBLEM`), ein Kandidat, der eine Ressourcengrenze überschreitet, eine ID-Kollision
  zwischen Verzeichnissen, ein Konflikt an einem exklusiven Erweiterungspunkt, ein Force-Load eines
  Plugins, das seine Sicherheitsprüfung nicht bestanden hat, eine über
  `write<ChecksumSecurityStrategy>` persistierte Prüfsumme, eine persistierte generische
  Sicherheitsausnahme (`forceLoad(..., persistException = true)`) und jede dadurch übersprungene
  nachfolgende Prüfung, die Verwendung von `NoPersistenceStrategy`, ein nicht proxy-fähiger,
  eigener Rückgabetyp, eine unmittelbar bevorstehende `ExceptionHandlingAction.CRASH`, ein
  Sandbox-Verstoß (`SECURITY WARNING - potential attack: ...` für einen blockierten API-Aufruf,
  `Sandbox violation for plugin ...` für einen `callTimeout`-Timeout oder einen unerwartet beendeten
  Subprozess der Prozessisolation).
* **`DEBUG`** - die enthaltenen Erweiterungen eines Plugins (Schlüssel, Implementierungsklasse), ein
  Decorator, der eine `implementation`-Klasse instanziiert, Lifecycle-Übergänge
  (`onLoad`/`onEnable`/`onDisable`/`onUnload`).
* **`ERROR`** - ein durch `ExceptionHandlingAction.UNLOAD` zwangsweise deaktiviertes Plugin, ein
  aufgrund eines Sandbox-Verstoßes zwangsweise entladenes und als `POTENTIAL_ATTACK` markiertes
  Plugin, ein Plugin, dessen `onLoad`/`onEnable` (beim Auflösen seiner Erweiterungen) oder
  `onDisable`/`onUnload` (während `unload()`) sein Sandbox-Timeout überschritten hat, eine während
  `PluginManager.scan()` erkannte zyklische Plugin-Abhängigkeit (kein Kandidat dieses Scans wird
  geladen).
* **`TRACE`** - feingranulare Details innerhalb einer einzelnen Sicherheitsstrategieprüfung
  (Signatureintrag-Verifikation, Prüfsummenberechnung, Reihenfolge der Kettenauswertung) sowie jede
  andere sicherheits- und sandboxrelevante Entscheidung (Auflösung öffentlicher Schlüssel,
  Guard-Prüfungen der Sandbox, Sichtbarkeit von Abhängigkeiten, Auflösung der Ausnahmebehandlung).

## Referenz `PluginScanStatus`

Jeder Plugin-Kandidat endet mit genau einem dieser Status in
`PluginManager.scanResults`/dem Ergebnis von `PluginScanner.scan`:

| Status                  | Bedeutung                                                                                              | `manifest` |
|--------------------------|-------------------------------------------------------------------------------------------------------|------------|
| `LOADED`                | Manifest gültig, Sicherheit bestanden, ID-Kollision/`minVersion` bestanden, Classloader erfolgreich erstellt. | vorhanden  |
| `MANIFEST_NOT_FOUND`     | Keine Manifestdatei für diesen Kandidaten gefunden.                                                      | `null`     |
| `MANIFEST_INVALID`      | Ein Manifest wurde gefunden, hat aber die Schemavalidierung nicht bestanden oder konnte nicht gemappt werden. | `null`     |
| `SECURITY_PROBLEM`      | Jede Strategie der Sicherheitskette des Verzeichnisses ist fehlgeschlagen. Siehe [Sicherheit](security.de.md). | vorhanden  |
| `ID_COLLISION`          | Gegen einen anderen Kandidaten derselben Plugin-ID aus einem anderen Verzeichnis verloren (oder gleichauf). | vorhanden  |
| `MIN_VERSION_VIOLATION` | Das `minVersion` des Manifests ist neuer als die konfigurierte `hostVersion`.                             | vorhanden  |
| `LOAD_FAILED`           | Alle vorherigen Prüfungen bestanden, aber `PluginLoader.load` ist dennoch fehlgeschlagen (z. B. eine fehlende erforderliche Abhängigkeit). | vorhanden  |
| `POTENTIAL_ATTACK`      | Ein geladenes Plugin hat zur Laufzeit gegen seine Sandbox-Policy verstoßen (oder das Limit der Aufruf-Timeouts erreicht) und wurde zwangsweise entladen. Kann nie per Force-Load geladen werden. Siehe [Laufzeit-Sandbox](sandbox.de.md#sandbox-verstoe-und-potential_attack). | vorhanden  |

Nur `MANIFEST_NOT_FOUND`/`MANIFEST_INVALID` setzen `manifest` zurück - jeder andere Status außer
`LOADED` behält es, sodass ein Host die deklarierte ID/Version des Kandidaten weiterhin einsehen und,
falls er sich dafür entscheidet, ihn dennoch per `PluginManager.forceLoad` laden kann. Die einzige
Ausnahme ist `POTENTIAL_ATTACK`: Dafür gibt es bewusst keine Host-Überschreibung. `forceLoad`,
`reload` und `reactivate` werfen für ein solches Plugin alle eine `IllegalStateException` - `reload`
und `reactivate`, bevor sie eine erneute Sicherheitsprüfung ausführen, sodass nichts geladen oder als
aktiviert persistiert wird.

## ID-Kollision

Tragen zwei Verzeichnisse einen Kandidaten mit derselben Plugin-ID bei, wird dies rein anhand der
Version aufgelöst (Maven-Versionsschema, siehe `IdCollisionResolver`):

* Die strikt höhere Version gewinnt; der Rest der Gruppe wird als `ID_COLLISION` markiert und als
  `WARN` protokolliert.
* Ist die höchste Version zwischen zwei oder mehr Kandidaten gleich, wird die **gesamte Gruppe**
  sofort abgelehnt (`ID_COLLISION`), als `WARN`-Sicherheitswarnung protokolliert - es gibt keine
  weitere Konfliktauflösung (z. B. per Prüfsumme).

## Konflikt an einem exklusiven Erweiterungspunkt

Ist ein Erweiterungspunkt als `exclusive = true` deklariert und tragen zwei unabhängig installierte
Plugins beide zu seinem Schlüssel bei, **werden die Erweiterungen aller beitragenden Plugins
verworfen** (nicht nur die einzelne Registrierung): Keine davon erscheint in `extensionsByKey`, sodass
`getExtensions`/`getFirstExtension` nichts von ihnen zurückliefern, und `ExtensionAggregator` meldet
jedes dieser Plugins als `REJECTED_EXCLUSIVE_CONFLICT`. Der Konflikt wird als `WARN` protokolliert,
die alle Plugin-IDs und den Schlüssel nennt. `PluginManager` selbst reagiert nicht auf diesen Status:
Die Plugins bleiben in `loadedPlugins`, und ihre `scanResults`-Einträge behalten den Status `LOADED`,
sodass das `WARN`-Log die Stelle ist, an der nachzusehen ist. Ihre `onLoad`/`onEnable`-Hooks sind zu
diesem Zeitpunkt bereits gelaufen, da jede Erweiterung instanziiert wird, bevor der Konflikt erkannt
wird. Dies ist ein Konflikt der konkreten installierten Plugin-Kombination, kein Fehler in der
Erweiterungspunkt-Deklaration des Hosts.

## `minVersion`-Ablehnung

Das `manifest.minVersion` eines Kandidaten wird mit
`PluginManagerConfiguration.hostVersion` verglichen (Maven-Versionsschema). Ist `hostVersion` neuer
als oder gleich `minVersion`, besteht die Prüfung; ist `hostVersion` älter, wird der Kandidat als
`MIN_VERSION_VIOLATION` markiert und nie geladen. `hostVersion == null` (der Standard) überspringt
diese Prüfung vollständig.

## Zyklische Plugin-Abhängigkeit

Bilden die für das Laden in einem `scan()`-Aufruf ausgewählten Plugins einen Abhängigkeitszyklus
(sowohl über `required`- als auch über `optional`-Kanten), erkennt `DependencyGraph.topologicalOrder`
dies, und **kein Kandidat dieses Scan-Laufs wird überhaupt geladen** - protokolliert als `ERROR`,
das den Zyklus nennt. Jeder betroffene Kandidat wird mit dem Zyklus in seiner `errorMessage` als
`LOAD_FAILED` markiert.
