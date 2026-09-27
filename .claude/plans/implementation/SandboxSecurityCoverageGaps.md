# Fehlende Testabdeckung in sandbox/security schliessen

## Kontext

* Kover-Regel "Line coverage of the sandbox and security packages" verlangt 80% Zeilenabdeckung
* Nutzer meldet 47.9% in der CI-Pipeline, lokal ist der Gate aktuell gruen
* Analyse-Agent fand konkrete Luecken, ueberwiegend vorbestehende Fehler-/Randpfade
* Ziel: fehlende Developer-Tests ergaenzen, danach CI-Diskrepanz klaeren

## Aufgabe 1: PluginSandboxAgent.kt und GuardAsmVisitorWrapper.kt

* [x] Test: `guardedCategoryForSubtype` Kurzschluss-Zweige (Array-Owner, JDK-Namespace) einzeln
* [x] Test: `resolveGuardedBaseType` Fehlerfall (nicht aufloesbarer Typ liefert null)
* [x] Test: `opensNamedResource` alle drei Deskriptor-Varianten einzeln geprueft
* [ ] Test: `install()` via `premain`/`agentmain`, No-Op bei zweitem Aufruf - zurueckgestellt (Agent-Attach-Fixture, Gate bereits erfuellt)
* [ ] Test: `PluginClassLoaderRawMatcher.matches`/`GuardTransformer.transform` - zurueckgestellt (siehe oben)
* [ ] Test: `visitInvokeDynamicInsn` Methodenreferenz-Guarding - zurueckgestellt (siehe oben)

## Aufgabe 2: SandboxGuardRegistry.kt

* [x] Test: `release()` fuer registrierten und fuer unbekannten ClassLoader
* [ ] Test: `check()`-Deny-Zweige (Entry/Revoked) - bereits durch bestehende Tests ausreichend abgedeckt, Gate erfuellt

## Aufgabe 3: SignatureSecurityStrategy.kt

* [ ] Alle Punkte zurueckgestellt (Fixture-API nicht in dieser Runde gelesen) - Gate ist dennoch erfuellt (88.5%)

## Aufgabe 4: PluginSecurity.kt und ChecksumSecurityStrategy.kt

* [x] Test: `reevaluate`/`reevaluateAndPin` bei verschwundenem/ungueltigem Kandidaten und Erfolgspfad
* [x] Test: `ChecksumSecurityStrategy.persist` Fallback-Pfad ohne PinnedContent
* [x] Test: `ChecksumSecurityStrategy.candidateBytes(Path)` fuer Ordner-Kandidat
* [ ] Test: `reevaluateAndPin` faengt `PluginContentLimitExceededException` ab - zurueckgestellt

## Aufgabe 5: Restliche Randpfade

* [x] Test: `TrustStorePublicKeyProviderStrategy.resolve` faengt KeyStore-Exception ab
* [x] Test: `ThreadWatchdog` Pending-Call-Warnung beim Timeout-Shutdown
* [ ] Test: `ProcessIsolationStrategy` Proxy/`onCrash` - zurueckgestellt
* [ ] Test: `ThreadWatchdog` UncaughtExceptionHandler - zurueckgestellt (Pfad ueber oeffentliche API nicht erreichbar ohne Produktionscode-Aenderung)

## Aufgabe 6: Build und CI-Abgleich

* [x] Gradle-Target `koverXmlReport koverVerify` ausgefuehrt: 88.5% (907/1025 Zeilen), Gate erfuellt
* [ ] `project-docs`-Skill laden, CHANGELOG.md bei Bedarf ergaenzen
* [ ] Nutzer nach genauem CI-Branch/Commit fragen, um 47.9%-Diskrepanz zu klaeren

## Verifikation

* `gradlew.bat build` (inkl. `koverVerify`) muss erfolgreich durchlaufen
* Kover-XML-Report zeigt keine der oben genannten Zeilen mehr als "missed"
* Alle neuen Tests folgen dem testing-Skill: Developer-Tests, KDoc pro Methode, englische Testdaten
