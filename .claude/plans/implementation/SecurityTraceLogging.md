# TRACE-Logging fuer Security-Mechanismen

## Kontext

* Sicherheitsentscheidungen (Signatur, Checksumme, Sandbox, Dependency-Sichtbarkeit, Exception-Policy) sind aktuell nur teilweise auf TRACE geloggt
* Ziel: jede Security-Strategie- und Policy-Entscheidung TRACE-nachvollziehbar machen (welches Verfahren, welche Strategy, was gerade geprueft wird)
* Bestehendes Muster (SLF4J, `private val logger = LoggerFactory.getLogger(X::class.java)`, `{}`-Platzhalter, englische Meldungen) wird beibehalten und erweitert

## Aufgabe 1: security/ - Strategy-Ketten verdichten

* [x] `PluginSecurity.kt`: TRACE fuer `rescan()`-Ergebnis und `effectiveChain()`-Aufloesung ergaenzt
* [x] `SignatureSecurityStrategy.kt`: TRACE pro CodeSigner-Abgleich ergaenzt
* [x] `ChecksumSecurityStrategy.kt`: TRACE fuer `digestsEqual`-Vergleichsergebnis ergaenzt
* [x] `InsecureSecurityStrategy.kt`: TRACE-Hinweis, dass Sicherheitspruefung bewusst umgangen wird
* [x] `security/checksum/DigestComparison.kt`: Logger eingefuehrt, TRACE fuer Digest-Vergleich (`PersistableSecurityStrategy.kt`/`ChecksumAlgorithm.kt` enthalten keine eigene Entscheidungslogik, daher unveraendert)
* [x] `publickey/TrustStorePublicKeyProviderStrategy.kt`: TRACE fuer erfolgreiche Key-Aufloesung ergaenzt
* [x] `publickey/DirectPublicKeyProviderStrategy.kt`: Logger eingefuehrt, TRACE fuer Lookup-Ergebnis

## Aufgabe 2: sandbox/ - Verletzungs- und Policy-Pfad

* [x] `PluginSandbox.kt`: TRACE fuer activate/deactivate-Ablauf und `effectivePolicy()`-Aufloesung ergaenzt
* [x] `sandbox/agent/SandboxGuardRegistry.kt`: Logger eingefuehrt, TRACE fuer `check()`, `register()`, `revoke()`, `release()` (guarded via `isTraceEnabled` im heissen Pfad)
* [x] `AgentInstrumentationStrategy.kt`: TRACE fuer `activate()`-Ablauf und Guard-Registrierung ergaenzt
* [x] `GuardAsmVisitorWrapper.kt`: Logger eingefuehrt, TRACE pro instrumentierter Klasse/Call-Site
* [x] `ThreadWatchdog.kt`: TRACE fuer ungoverned/reentrant/governed Pruefpfad ergaenzt
* [x] `sandbox/process/ProcessIsolationStrategy.kt`: Logger eingefuehrt, TRACE fuer Aktivierung und Violation-Weiterleitung
* [x] `sandbox/agent/PluginSandboxAgent.kt`: TRACE vor Agent-Installation ergaenzt

## Aufgabe 3: classloader/ und exception/ - Zugriffs- und Reaktionspolicy

* [x] `classloader/DisallowPluginDependencyStrategy.kt`, `LocationPluginDependencyStrategy.kt`, `UnrestrictedPluginDependencyStrategy.kt`: Logger eingefuehrt, TRACE mit from/to/Ergebnis
* [x] `classloader/DependencyGraph.kt`: TRACE um `strategy.isVisible(...)`-Aufruf ergaenzt
* [x] `exception/DefaultExceptionHandlingStrategy.kt`: Logger eingefuehrt, TRACE fuer `lookup()`-Treffer und `resolve()`-Entscheidung
* [x] `PluginManager.kt`: TRACE vor `scan()`-Sicherheitsketten-Durchlauf ergaenzt (violation-getriebener Unload hat bereits dichte WARN/ERROR-Logs, daher hier bewusst nicht zusaetzlich verdichtet)

## Aufgabe 4: Build und Doku

* [ ] Build mit Gradle-Target `build` ueber Agent ausfuehren (Konkurrenz-Regel), Ergebnis pruefen
* [ ] `project-docs`-Skill laden und CHANGELOG.md / betroffene KDoc-Stellen pruefen und ergaenzen

## Verifikation

* Gradle-Build (`build`) muss erfolgreich durchlaufen
* Stichprobenartig Logger-Konfiguration auf TRACE stellen und bestehende Tests fuer Security/Sandbox/Classloader ausfuehren, TRACE-Ausgabe pruefen
