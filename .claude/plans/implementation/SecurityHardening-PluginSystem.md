# Implementierungsplan: Härtung des Plugin-Systems

## Aufgabe 1: Auflösung der Guard-Klasse im Plugin-ClassLoader erzwingen

- [ ] In `PluginClassLoader` Konstante für das geschützte Paketpräfix `org.pcsoft.framework.pluggiat.` anlegen
- [ ] In `loadClass` vor `isWhitelisted` und vor `findClass` auf dieses Präfix prüfen
- [ ] Treffer unbedingt an `hostClassLoader` delegieren, ohne Rückfall auf `findClass`
- [ ] Fehlschlagende Delegation als `ClassNotFoundException` weitergeben, nicht verschlucken
- [ ] `findResource`/`findResources` in `PinnedPluginClassLoader` für dieses Präfix ebenfalls sperren
- [ ] KDoc von `PluginClassLoader` und `SdkWhitelistEntry` um die neue Auflösungsreihenfolge ergänzen

## Aufgabe 2: Guard-Prüfung auf fail-closed umstellen

- [ ] In `SandboxGuardRegistry` Zustand `Revoked` neben `Entry` einführen
- [ ] `unregister` durch `revoke` ersetzen, das den ClassLoader auf `Revoked` setzt
- [ ] `check` wirft für einen widerrufenen ClassLoader unconditional `SandboxViolationException`
- [ ] Neue Methode `release` für endgültiges Entfernen beim Verwerfen des ClassLoaders
- [ ] `PluginSandbox.deactivate` ruft `revoke` statt `unregister`
- [ ] `PluginManager.handleSandboxViolation` behandelt Verstöße auch ohne Eintrag in `loadedPlugins`
- [ ] `reaggregateExtensions` aus `handleSandboxViolation` in eigenen Host-Thread verlagern

## Aufgabe 3: Guard-Tabelle um offene Aufrufpfade erweitern

- [ ] `java/net/URLClassLoader` als `REFLECTION` in `guardedCategoryFor` aufnehmen
- [ ] `java/util/ServiceLoader` als `REFLECTION` aufnehmen
- [ ] `com/sun/net/httpserver/` als `NETWORK` in `GUARDED_PACKAGE_PREFIXES` aufnehmen
- [ ] `java/util/concurrent/ForkJoinTask` als `THREAD_CREATION` aufnehmen
- [ ] `parallel`/`parallelStream` auf `java/util/stream/`-Ownern als `THREAD_CREATION` aufnehmen
- [ ] `java/lang/ModuleLayer` als `REFLECTION` aufnehmen
- [ ] `URLClassLoader` in `HIERARCHY_BASE_TYPES` über `ClassLoader` abgedeckt verifizieren

## Aufgabe 4: IPC der Prozessisolation authentifizieren und begrenzen

- [ ] In `PluginProcessManager.start` Token über `SecureRandom` erzeugen
- [ ] Token als weiteres Programmargument an `SubprocessBootstrapMain` übergeben
- [ ] `ProcessIpcServer` erhält Token im Konstruktor
- [ ] `ProcessIpcClient` sendet Token als erstes Feld jedes `ProcessCall`
- [ ] `BerCodec.writeCall`/`readCall` um das Token-Feld erweitern
- [ ] `ProcessIpcServer.handle` verwirft Aufrufe mit abweichendem Token über `MessageDigest.isEqual`
- [ ] `soTimeout` auf jedem akzeptierten Socket in `serveForever` setzen
- [ ] `ASN1InputStream` in `BerCodec.readSequence` mit Größenlimit instanziieren
- [ ] Konstante für das Nachrichtenlimit in `BerCodec` definieren

## Aufgabe 5: Keyserver-Antwort gegen die angefragte Key-ID prüfen

- [ ] In `fetchAndParse` Fingerprint des gewählten `PGPPublicKey` hexadezimal bilden
- [ ] Fingerprint und `keyID` gegen `normalizedKeyId` als Suffix vergleichen, case-insensitiv
- [ ] Bei Abweichung `null` zurückgeben und WARN loggen
- [ ] Schlüsselringe durchsuchen statt nur den ersten zu nehmen
- [ ] `keyserverBaseUrl` im Konstruktor auf `https://` prüfen und sonst `IllegalArgumentException` werfen
- [ ] KDoc um die Fingerprint-Bindung ergänzen

## Aufgabe 6: Sandbox-Policy im isolierten Subprozess durchsetzen

- [ ] `PluginProcessManager.start` übergibt `-javaagent` mit dem eigenen JAR an den Subprozess
- [ ] Pfad des Agent-JAR aus der Code-Source von `PluginSandboxAgent` ermitteln
- [ ] Erlaubte `SandboxApiCategory`-Werte als weiteres Programmargument übergeben
- [ ] `SubprocessBootstrapMain` registriert die Policy im `SandboxGuardRegistry` des Subprozesses
- [ ] Klassenlader des Subprozesses auf `PluginClassLoader` statt `URLClassLoader` umstellen
- [ ] `ProcessIsolationStrategy.activate` prüft `PluginSandboxAgent`-Verfügbarkeit für `requiresApiMediation`
- [ ] `PluginProcessClasspath` erhält Überladung für `PinnedPluginContent`
- [ ] Gepinnte Bytes in das temporäre Arbeitsverzeichnis des Subprozesses schreiben
- [ ] `ProcessIsolationStrategy.createExtensionProxy` um den `PinnedPluginContent`-Parameter erweitern
- [ ] `ExtensionAggregator.resolveAndEnforce` reicht den gepinnten Inhalt durch

## Aufgabe 7: Ressourcengrenzen beim Einlesen von Kandidaten

- [ ] Konstanten für maximale Kandidatengröße, Gesamtentpackgröße und Schachtelungstiefe anlegen
- [ ] `PinnedPluginContentReader.read` prüft `Files.size` vor `readAllBytes`
- [ ] Überschreitung als eigene Exception melden, Kandidat als `SECURITY_PROBLEM` führen
- [ ] `resolveJarEntries` erhält Tiefenparameter und bricht bei Überschreitung ab
- [ ] `resolveEntriesFromZipBytes` summiert entpackte Bytes und bricht bei Überschreitung ab
- [ ] Einzelne Entry-Größe gegen ein Limit prüfen, bevor `readBytes` läuft
- [ ] `ManifestParser.parse(InputStream)` auf ein Größenlimit begrenzen

## Aufgabe 8: Persistenz und Integritätsschutz härten

- [ ] HMAC-Vergleich in `IntegrityProtectedPersistenceStrategy.read` auf `MessageDigest.isEqual` umstellen
- [ ] `computeHmac` mit längenpräfigierten Feldern statt Leerzeichen-Verkettung bilden
- [ ] Bestehende HMAC-Werte als ungültig behandeln, statt ein Migrationsformat einzuführen
- [ ] Schlüsseldatei in `loadOrCreateKey` mit `PosixFilePermissions` 600 bzw. Windows-ACL anlegen
- [ ] `FilePersistenceStrategy.save` schreibt über temporäre Datei mit `ATOMIC_MOVE`
- [ ] Zustandsdatei in `FilePersistenceStrategy.save` mit denselben restriktiven Rechten anlegen
- [ ] `ChecksumSecurityStrategy.persist` um Überladung mit `PinnedPluginContent` erweitern
- [ ] `PluginManager.write` übergibt den gepinnten Inhalt an `persist`
- [ ] `candidateBytes(PinnedPluginContent.Multi)` mit längenpräfigierter Verkettung bilden
- [ ] `DatabasePersistenceStrategy.upsert` in eine Transaktion klammern

## Aufgabe 9: Manifest- und Plugin-ID-Konsistenz

- [ ] `PluginManifestLookup.scanJarStream` wählt das letzte passende Manifest-Entry statt des ersten
- [ ] `findManifestEntryName` und `scanSingleJar` auf dieselbe Reihenfolge ausrichten
- [ ] `plugin-manifest.schema.json` erhält `pattern` und `maxLength` für `id`
- [ ] Gleiches `pattern` für `dependencies[].id` setzen
- [ ] `SchemaDataClassSyncTest`-Erwartungen an das neue Schema anpassen
- [ ] `FilePersistenceStrategy` flacht mit einem in IDs verbotenen Trennzeichen ab
- [ ] `fromFlatProperties` trennt am neuen Trennzeichen statt am letzten Punkt

## Aufgabe 10: Restliche Härtungspunkte

- [ ] `PluginClassLoader.loadClass` fängt nur `ClassNotFoundException` statt jedes `Throwable`
- [ ] `LinkageError` und `ClassFormatError` aus `findClass` unverändert weiterwerfen
- [ ] `SignatureSecurityStrategy` lehnt einen Signer ohne `X509Certificate` als Fehlschlag ab
- [ ] `isSigningMetadataEntry` gilt nur für tatsächlich vom Verifier konsumierte Signaturdateien
- [ ] Nicht konsumierte `META-INF`-Einträge weiter der Signaturpflicht unterwerfen
- [ ] `PluginSecurity.rescanFailureMessage` nutzt das Ergebnis des ersten Scans
- [ ] `PluginSecurity.rescan` ersetzt `first` durch `firstOrNull`

## Aufgabe 11: Gradle-Testaufbau für den Agent

- [ ] `ci-pipeline`-Skill laden
- [ ] Neue Source-Set-Konfiguration `sandboxAgentTest` in `build.gradle.kts` anlegen
- [ ] Task `sandboxAgentTest` mit `-javaagent` auf das `jar`-Artefakt konfigurieren
- [ ] Task von `jar` abhängig machen und an `check` anhängen
- [ ] Kover-Verifizierung mit Mindestabdeckung für `sandbox`- und `security`-Pakete konfigurieren
- [ ] `.github`-Workflows um den neuen Task ergänzen

## Aufgabe 12: Tests Sandbox

- [ ] `testing`-Skill laden
- [ ] End-to-End-Test: geguardeter Aufruf aus einem `PluginClassLoader` wirft `SandboxViolationException`
- [ ] Test: Plugin-eigene `SandboxGuardRegistry` im JAR wird nicht geladen
- [ ] Test: erlaubte Kategorie läuft nach Instrumentierung ohne `NoClassDefFoundError` durch
- [ ] Test: `check` wirft nach `revoke` für jede Kategorie
- [ ] Test: Verstoß eines Plugin-Threads nach Entladung bleibt blockiert
- [ ] Test: `URLClassLoader`, `ServiceLoader` und `parallelStream` liefern eine Kategorie
- [ ] Test: fremde IPC-Verbindung ohne Token wird abgewiesen
- [ ] Test: `BerCodec` lehnt unbekanntes Tag, falsche Elementzahl und Überlänge ab
- [ ] Test: Subprozess blockiert eine nicht erlaubte Kategorie

## Aufgabe 13: Tests Security und Persistenz

- [ ] `testing`-Skill laden
- [ ] Test: nachträglich eingefügtes unsigniertes Entry lässt die Signaturprüfung scheitern
- [ ] Test: zusätzliche, nicht in `plugin-checksums.txt` gelistete JAR wird abgelehnt
- [ ] Test: Keyserver-Antwort mit abweichender Key-ID wird abgelehnt
- [ ] Test: `keyserverBaseUrl` ohne `https` wird abgelehnt
- [ ] Test: verschobener HMAC zwischen Plugin-IDs und Keys wird erkannt
- [ ] Test: gelöschter HMAC-Eintrag führt zu `null`
- [ ] Test: Plugin kann Framework- und nicht gelistete Host-Klassen nicht überladen
- [ ] Test: Kandidat über Größenlimit und geschachteltes JAR über Tiefenlimit werden abgelehnt
- [ ] Test: Dateiaustausch zwischen Security-Check und Load bleibt wirkungslos

## Aufgabe 14: Build und Dokumentation

- [ ] Gradle-Ziel `build` über Agent ausführen
- [ ] Gradle-Task `sandboxAgentTest` über Agent ausführen
- [ ] `project-docs`-Skill laden
- [ ] README, MkDocs, KDoc und `CHANGELOG.md` prüfen und anpassen
- [ ] `docs/docs/host-integration/sandbox.md` und `sdk-whitelist.md` aktualisieren
- [ ] `translation`-Skill laden und geänderte MkDocs-Seiten übersetzen lassen
- [ ] Plan nach Abschluss mit `git rm` entfernen
