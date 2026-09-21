# Implementierungsplan: Lifecycle & Fehlerisolation

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-06.
Voraussetzung: IP-02, IP-05.
Enthält zwei Abweichungen/Erweiterungen mit Wirkung über IP-06 hinaus, im Dialog mit dem Nutzer so entschieden:
- Migration von `ChecksumPersistenceCallback` (COMPLETED IP-04) auf das neue Persistence-Modul.
- Vorziehen der zentralen `PluginManager`-Klasse (eigentlich IP-07-Umfang "Öffentliche API"), da IP-06
  bereits einen Ort für host-weite Konfiguration (Persistence-Strategie, Exception-Handling-Strategie)
  benötigt; endgültige Ausbaustufe (Gesamtablauf, ID-Kollision, minVersion) bleibt Aufgabe von IP-07.

## Aufgabe 1: `PluginManager` (zentraler Einstiegspunkt, Root-Paket)

- [ ] Klasse `PluginManager` im Root-Paket (`org.pcsoft.framework.pluggiat`) anlegen
- [ ] Kotlin-Builder-Funktion `pluginManager(applyConfig: Config.() -> Unit): PluginManager` im Root-Paket
- [ ] `Config`-Klasse (Builder-Empfänger) nimmt Plugin-Orte, Default-Security-Ketten, globale
      `PluginDependencyStrategy`, `PluginPersistenceStrategy`, Default-`ExceptionHandlingStrategy`,
      SDK-Whitelist und Host-Version entgegen
- [ ] `PluginManager` stellt vorkonfigurierte Instanzen bereit: `scanner`, `security`, `loader`
- [ ] `PluginManager` ist vorerst nur Konfigurations-/Zugriffs-Fassade; Gesamtablauf bleibt IP-07
- [ ] Bestehende Konstruktor-Parameter von `PluginScanner`/`PluginLoader` bleiben zusätzlich nutzbar

## Aufgabe 2: Lifecycle-Hooks

- [ ] Interface `PluginLifecycle` mit Hook-Methoden `onLoad`/`onEnable`/`onDisable`/`onUnload` definieren, optional von beliebigen Klassen implementierbar
- [ ] Hooks mit Default-Implementierung leer gestalten (Interface-Default-Methoden)
- [ ] Aufrufreihenfolge `onLoad` vor `onEnable`, `onDisable` vor `onUnload` festlegen
- [ ] Einschränkung dokumentieren: implementieren mehrere Klassen eines Plugins `PluginLifecycle`, ist deren Aufrufreihenfolge untereinander NICHT deterministisch
- [ ] Zweiphasen-Trennung dient der Ladereihenfolge bei Abhängigkeiten (alle `onLoad` vor allen `onEnable`, symmetrisch beim Abbau), nicht der Abbildung von Enabled/Disabled
- [ ] `onUnload` gefolgt vom Verwerfen/Schließen des Plugin-`ClassLoader` (IP-05) als festen Abschluss jeder Deaktivierung definieren
- [ ] Reaktivierung erfordert wegen des verworfenen ClassLoaders einen vollständigen Neu-Ladevorgang, kein bloßes Wieder-Einschalten eines Flags

## Aufgabe 3: Persistence-Modul (`PluginPersistenceStrategy`)

- [ ] Neues Package `persistence` anlegen
- [ ] Interface `PluginPersistenceStrategy` mit `read(pluginId, key): String?` und `write(pluginId, key, value)` (generisches Key-Value-Modell je Plugin-ID)
- [ ] `NoPersistenceStrategy` (Default): `read` liefert `null`, `write` No-Op, WARN-Log beim ersten Einsatz ("nicht empfohlen für produktive Software")
- [ ] `CustomPersistenceStrategy(readCallback, writeCallback)`: delegiert an Host-Funktions-Interfaces
- [ ] `FilePersistenceStrategy(path, format: PersistenceFileFormat = PROPERTIES)`: Enum `PROPERTIES`/`JSON`/`YAML`/`XML`, alle ohne neues Dependency (Properties, Jackson, `Properties.storeToXML`)
- [ ] `DatabasePersistenceStrategy(dataSource: javax.sql.DataSource)`: reines JDBC, legt Tabelle `plugin_state(plugin_id, key, value)` selbst an
- [ ] Alle Implementierungen `public`, frei durch eigene Implementierungen erweiterbar
- [ ] Migration IP-04: `ChecksumSecurityStrategy` nutzt `PluginPersistenceStrategy` (Key `"checksum"`) statt `ChecksumPersistenceCallback`; `ChecksumPersistenceCallback` wird entfernt
- [ ] Enabled/Disabled-Status (Aufgabe 4) nutzt dieselbe `PluginPersistenceStrategy`-Instanz
- [ ] Konfiguration ausschließlich über `PluginManager.Config`, eine Instanz für das gesamte Framework

## Aufgabe 4: Enabled/Disabled-Status

- [ ] Enabled/Disabled-Status wird über `PluginPersistenceStrategy` gelesen/geschrieben
- [ ] Deaktiviertes Plugin bleibt gescannt, Extension-Klassen werden NICHT geladen/instanziiert
- [ ] Enabled/Disabled-Status wird VOR dem Extension-Decorator-Mapping (IP-02) geprüft
- [ ] Betrifft ALLE Klassen der Extension, nicht nur `implementation`: Decorator darf die Klasse für ein deaktiviertes Plugin nicht einmal per Reflection auflösen (verhindert transitives Laden von Elternklassen/Interfaces/Feldtypen)
- [ ] Deaktivierungsgrund (Nutzer vs. Laufzeitfehler) unterscheidbar machen, über eigenen Persistence-Key
- [ ] Jede Statusänderung wird sofort über `PluginPersistenceStrategy.write` persistiert
- [ ] Framework hält keinen eigenen, losgelösten Enabled/Disabled-Zustand; `read` ist Single Source of Truth
- [ ] Abweichung von Feature Plan FP-001 Abschnitt 5 vermerken: Reihenfolge auf "Status-Prüfung vor Decorator-Mapping" korrigieren

## Aufgabe 5: Security-Re-Check bei Reaktivierung (öffentliche `PluginSecurity`-Klasse)

- [ ] `PluginSecurityChainEvaluator` (IP-04) zu `PluginSecurity` umbenennen
- [ ] `PluginSecurity` als frei zugängliche, öffentliche API analog zu `PluginLoader`, zusätzlich über `PluginManager` vorkonfiguriert beziehbar
- [ ] `PluginScanner` weiterhin über `PluginSecurity` delegieren (reine Umbenennung)
- [ ] Reaktivierungsablauf `PluginSecurity` → `PluginLoader`: Nachladen prüft zuerst erneut die Sicherheits-Kette, erst danach `PluginLoader.load`
- [ ] Re-Check liest den Kandidaten am ursprünglichen Pfad erneut über die zuständige `PluginScanStrategy` ein
- [ ] Fehlgeschlagener Re-Check hält das Plugin deaktiviert (Status/Grund aktualisieren), kein automatischer Force-Load
- [ ] Host kann `PluginSecurity` auch unabhängig von einer Reaktivierung direkt aufrufen

## Aufgabe 6: Laufzeit-Fehlerisolation über `ExceptionHandlingStrategy`

- [ ] Exception-Typen `PluginExecutionException` und `PluginFatalException` (empfohlen, nicht verpflichtend)
- [ ] Enum `ExceptionHandlingAction` mit `IGNORE`, `UNLOAD`, `CRASH` (CRASH bewusst nicht empfohlen)
- [ ] Interface `ExceptionHandlingStrategy`, konfigurierbar über `Map<KClass<out Throwable>, ExceptionHandlingAction>`
- [ ] Map-Lookup läuft die Klassenhierarchie hoch (spezifischste Superklasse gewinnt), sonst Fallback auf `parent`/Default-Matrix
- [ ] `DefaultExceptionHandlingStrategy`: `PluginExecutionException`→IGNORE, `PluginFatalException`→UNLOAD, sonstige Checked→IGNORE, sonstige Unchecked→UNLOAD
- [ ] Eigene Strategien referenzieren optional einen `parent` zur Verschachtelung/Kette
- [ ] Es gibt ausschließlich eine host-weite Strategie (über `PluginManager.Config`), kein Registrierungsweg für eine plugin-individuelle Strategie
- [ ] Ein Plugin beeinflusst das Handling nur indirekt über die Wahl der nach außen dringenden Exception-Klasse
- [ ] `IGNORE`: Plugin bleibt aktiv, Vorfall protokolliert/im Ergebnis vermerkt
- [ ] `UNLOAD`: `onDisable`/`onUnload` sofern möglich, ClassLoader verwerfen/schließen, Status dauerhaft deaktiviert über `PluginPersistenceStrategy` (Grund: Laufzeitfehler)
- [ ] `CRASH`: `printStackTrace()`, danach sofortiger JVM-Abbruch (`Runtime.getRuntime().halt(...)`)
- [ ] Abhängigkeitsgraph (IP-05) bei `UNLOAD` über den Wegfall des ClassLoaders informieren

### Durchsetzung über einen Proxy (Erweiterung zu IP-02)

- [ ] Neue Dependency `net.bytebuddy:byte-buddy` + `org.objenesis:objenesis`, mit Nutzer abgestimmt
- [ ] Die vom Decorator (IP-02) ausgelieferte `implementation`-Instanz ist eine Proxy-Instanz über `T`, nicht die reale Plugin-Klasse
- [ ] `T` ist Interface → `java.lang.reflect.Proxy`; `T` ist offene Klasse → ByteBuddy-Subklasse, Instanziierung über Objenesis
- [ ] Interceptor delegiert jeden Aufruf an die reale Instanz, fängt jeden `Throwable` ab, löst ihn über die `ExceptionHandlingStrategy` auf
- [ ] `UNLOAD` erfolgt SYNCHRON innerhalb des Proxy-Aufrufs, bevor `PluginFatalException` geworfen wird
- [ ] Aus dem Host-Plugin-API dringen ausschließlich `PluginExecutionException`/`PluginFatalException` nach außen
- [ ] `ExtensionAggregator` liefert die reale Instanz weiterhin intern (Lifecycle-Hooks), dem Host wird nur die Proxy-Instanz zugänglich gemacht
- [ ] Validierung bei Registrierung in `ExtensionPointRegistry`: `T` muss proxybar sein (Interface oder offene, nicht-finale Klasse); Kotlin-Klassen sind standardmäßig `final` und müssen explizit `open` sein
- [ ] Ist `T` eine finale Klasse: ERROR-Log, betroffene Plugins für diesen Extension-Point können nicht geladen werden
- [ ] Dokumentierte Restgrenzen: finale Methoden, direkter Feldzugriff, `static`-Methoden bleiben grundsätzlich nicht abfangbar

### Rekursives Wrapping von Rückgabewerten (Factory-Fall)

- [ ] Rückgabewerte proxyter Methoden werden rekursiv nach demselben Mechanismus gewrappt, wenn der DEKLARIERTE Rückgabetyp proxy-eligibel ist (Interface oder offene Klasse)
- [ ] Native/Standard-Typen werden ausgenommen, unverändert durchgereicht, ohne Log: Primitives, `String`, finale Typen aus `java.*`/`javax.*`/`kotlin.*`/`javafx.*` (inkl. `javax.swing.*`), `null`
- [ ] Eigene (Nicht-Standard) finale Klasse als Rückgabewert: WARN-Log (Sicherheitsgarantie greift hier nicht), Wert wird trotzdem unverändert durchgereicht
- [ ] Arrays proxy-eligibler Komponententypen werden elementweise gewrappt
- [ ] `Collection<T>` proxy-eligibler Elementtypen werden elementweise gewrappt (Elementtyp über `Method.genericReturnType`)
- [ ] `Map<K, V>` mit proxy-eligiblem Value-Typ wird über die Values gewrappt, Keys unverändert
- [ ] Nicht ermittelbarer generischer Elementtyp (Rohtyp/Wildcard): unverändert durchreichen, WARN-Log
- [ ] Design-Regel für Host-APIs dokumentieren: jeder grenzüberschreitende Typ sollte proxy-eligibel sein

## Aufgabe 7: Logging

- [ ] DEBUG-Log je Lifecycle-Übergang
- [ ] INFO-Log bei dauerhafter Enabled/Disabled-Änderung über `PluginPersistenceStrategy`
- [ ] WARN-Log beim Einsatz von `NoPersistenceStrategy` (einmalig beim Start)
- [ ] ERROR-Log bei Zwangsdeaktivierung durch `ExceptionHandlingAction.UNLOAD`
- [ ] WARN-Log bei `ExceptionHandlingAction.CRASH` vor Durchführung

## Aufgabe 8: Tests zum Proxy-Verhalten

- [ ] Test: Proxy für Interface-`T` fängt Exception ab, wirft `PluginExecutionException`/`PluginFatalException` gemäß Matrix
- [ ] Test: Proxy für offene Kotlin-Klasse via ByteBuddy verhält sich identisch zum Interface-Proxy
- [ ] Test: finale Klasse als `T` wird bei Registrierung abgelehnt, ERROR-Log
- [ ] Test: `UNLOAD` schließt ClassLoader synchron innerhalb des Proxy-Aufrufs vor dem Werfen der Exception
- [ ] Test: rekursives Wrapping eines Factory-Rückgabewerts über mehrere Verschachtelungsebenen
- [ ] Test: primitive/String-Rückgabewerte werden unverändert durchgereicht, ohne Log
- [ ] Test: eigene finale Klasse als Rückgabewert erzeugt WARN-Log, Wert bleibt unverändert
- [ ] Test: Array und `Collection<T>` proxy-eligibler Elemente werden elementweise korrekt gewrappt
- [ ] Test: `Map<K, V>` mit proxy-eligiblem Value-Typ wird über die Values gewrappt, Keys unverändert
- [ ] Test: `CRASH` ruft `printStackTrace()` vor Abbruch auf; JVM-Abbruch über austauschbaren Test-Seam verifizieren

## Aufgabe 9: Tests

- [ ] Test: Lifecycle-Hooks werden in korrekter Reihenfolge aufgerufen
- [ ] Test: deaktiviertes Plugin liefert keine aktiven Extensions
- [ ] Test: `DefaultExceptionHandlingStrategy`-Matrix (alle vier Fälle) führt zum korrekten `ExceptionHandlingAction`
- [ ] Test: `UNLOAD` führt nur zur Zwangsdeaktivierung des betroffenen Plugins, übrige Plugins bleiben unbeeinträchtigt
- [ ] Test: verschachtelte `ExceptionHandlingStrategy` (Custom mit Parent) fällt korrekt zurück
- [ ] Test: Deaktivierung schließt/verwirft den Plugin-ClassLoader
- [ ] Test: Reaktivierung ruft `PluginSecurity` vor `PluginLoader` auf, fehlgeschlagener Re-Check verhindert das Nachladen
- [ ] Bestehende Tests zu `PluginSecurityChainEvaluator` auf `PluginSecurity` umziehen (Umbenennung)
- [ ] Test je `PluginPersistenceStrategy`-Implementierung (No-Op, Custom, File je Format, Database gegen In-Memory-JDBC)
- [ ] Test: `ChecksumSecurityStrategy` funktioniert unverändert über `PluginPersistenceStrategy` (Migration IP-04)
- [ ] Test: `PluginManager`-Builder liefert korrekt verdrahtete `scanner`/`security`/`loader`-Instanzen

## Aufgabe 10: Dokumentation

- [ ] Seite `docs/docs/plugin-development/lifecycle.md` erstellen, Hooks aus Plugin-Sicht erläutern
- [ ] Seite `docs/docs/plugin-development/error-handling.md` erstellen: empfohlene Exceptions, Standard-Matrix, Design-Regel für proxy-eligible Typen
- [ ] Abschnitt "Was sieht man beim Debugging" auf `error-handling.md`: Proxy-/ByteBuddy-Typ statt realer Klasse, `cause`-Kette, abweichende Referenzgleichheit/`toString()`, zusätzliche Interceptor-Stackframes
- [ ] Seite `docs/docs/host-integration/plugin-lifecycle-management.md` erstellen
- [ ] Seite `docs/docs/host-integration/persistence.md` erstellen: `PluginPersistenceStrategy`, Implementierungen, Empfehlung gegen `NoPersistenceStrategy` in Produktion
- [ ] Seite `docs/docs/host-integration/plugin-manager.md` erstellen: `PluginManager`, Builder-DSL, vorkonfigurierte Instanzen
- [ ] Enabled/Disabled-Callback und Deaktivierungsgründe für Host beschreiben
- [ ] Reaktivierungsablauf `PluginSecurity` → `PluginLoader` für Host-Integratoren beschreiben
- [ ] `docs/mkdocs.yml`-Navigation um alle neuen Seiten ergänzen
- [ ] Migration von `ChecksumPersistenceCallback` auf `PluginPersistenceStrategy` im CHANGELOG als Breaking Change vermerken

## Endzustand

- [ ] Jedes Plugin durchläuft nachvollziehbare Lifecycle-Phasen
- [ ] Enabled/Disabled-Status ist persistent über `PluginPersistenceStrategy` steuerbar
- [ ] Laufzeitfehler werden gemäß konfigurierbarer `ExceptionHandlingStrategy` behandelt (IGNORE/UNLOAD/CRASH)
- [ ] Ein deaktiviertes bzw. per UNLOAD behandeltes Plugin besitzt keinen aktiven ClassLoader mehr
- [ ] Reaktivierung ist nur nach erneut erfolgreicher `PluginSecurity`-Prüfung über `PluginLoader` möglich
- [ ] `PluginSecurity` ist eine öffentliche, vom Host direkt nutzbare Klasse analog zu `PluginLoader`
- [ ] `PluginPersistenceStrategy` ersetzt einheitlich Checksum-Persistenz (IP-04) und Enabled/Disabled-Status (IP-06)
- [ ] `PluginManager` (Root-Paket) ist zentraler, per Kotlin-Builder konfigurierbarer Einstiegspunkt
- [ ] Extension-Instanzen werden dem Host ausschließlich als Proxy übergeben, nach außen dringen nur `PluginExecutionException`/`PluginFatalException`
