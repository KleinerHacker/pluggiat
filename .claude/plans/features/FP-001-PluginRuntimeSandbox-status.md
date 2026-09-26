# Feature Status: Plugin Runtime Sandbox

Status: IN_PROGRESS

## Implementation Plans

| ID | Implementation Plan | Status |
|----|---------------------|--------|
| IP-01 | Sandbox-Grundmodell und Konfiguration | COMPLETED |
| IP-02 | Agent-basierte Bytecode-API-Mediation | COMPLETED |
| IP-03 | Thread- und Zeitlimit-Governance | NOT_STARTED |
| IP-04 | Prozessisolation für hochriskante Plugins | NOT_STARTED |
| IP-05 | Verstoßbehandlung und Beobachtbarkeit | NOT_STARTED |
| IP-06 | Persistenz-Integritätsschutz | COMPLETED |
| IP-07 | Checksum-/Signatur-Härtung (Byte-Pinning) | COMPLETED |
| IP-08 | Kollisionsauflösung nach Sicherheitsstatus filtern | COMPLETED |

## Overall Progress

62.5% (5/8 Implementierungsplänen abgeschlossen)

## Notes

IP-01 (Sandbox-Grundmodell, `PluginSandbox`-Fassade und Konfiguration) umgesetzt: neues Package
`org.pcsoft.framework.pluggiat.sandbox` mit `PluginSandboxPolicy`, `SandboxViolation`,
`SandboxCheckResult`, `PluginSandboxStrategy`/`NoOpSandboxStrategy` und der Fassade `PluginSandbox`
(`activate`/`runGoverned`/`reportViolation`/`deactivate`, aktuell vollständig No-Op).
`PluginManagerConfiguration.sandboxPolicies`/`defaultSandboxPolicy` und
`PluginLocation.sandboxOverride`/`PluginLocationBuilder.sandboxOverride` spiegeln das
Security-Chain-Muster; `PluginManager.sandbox` ruft `sandbox.activate(...)` nach jedem
`loader.load()` in `scan`, `reactivate` und `forceLoad` auf. Abweichung vom ursprünglichen Plan:
`sandbox.runGoverned` umschließt nur die direkten `PluginLifecycle`-Aufrufe in
`PluginManager.unload()`, da dies aktuell die einzige Stelle ist, an der `PluginManager` selbst
Lifecycle-Hooks aufruft; die Anbindung der `onLoad`/`onEnable`-Aufrufe in `ExtensionAggregator` ist
laut Architekturabschnitt Aufgabe von IP-03. Details siehe Notiz im (entfernten) Implementierungsplan
IP-01, festgehalten im zugehörigen Commit.

IP-06 (Persistenz-Integritätsschutz) umgesetzt: `IntegrityProtectedPersistenceStrategy` als
Decorator, `SecureRandom`-basierter Schlüssel in separater Schlüsseldatei, HMAC-Schutz je
`write`/`read`. Abweichung vom ursprünglichen Plan: der abgeleitete HMAC-Zusatzschlüssel verwendet
den Suffix `_hmac` statt `.hmac`, da ein Punkt darin die `.`-basierte Flach-Properties-Kodierung von
`FilePersistenceStrategy` (`PROPERTIES`/`XML`) beim Neuladen von der Platte falsch aufteilen würde.

Offene Fragen aus Abschnitt 9 des Feature Plans (fehlender SecurityManager auf JDK 25, Umfang des
Bouncy-Castle-Einsatzes für IP-04, Performance-Overhead, Speicher-/Funktionsumfang-Tradeoffs des
Byte-Pinnings bei IP-07) sollten vor Beginn von IP-04/IP-07 mit dem Nutzer geklärt werden - für IP-02
wurde die Host-seitige Java-Agent-Voraussetzung inzwischen geklärt (siehe unten) und ist umgesetzt.

IP-02 (Agent-basierte Bytecode-API-Mediation) umgesetzt: Java-Agent-Modul
`org.pcsoft.framework.pluggiat.sandbox.agent` (`PluginSandboxAgent` mit `premain`/`agentmain`,
Byte-Buddy-`AgentBuilder` gegen jede über `PluginClassLoader` geladene Klasse), `GuardAsmVisitorWrapper`
fügt vor `java.io.File`-/`java.net.Socket`-Konstruktoren, `ProcessBuilder.start()`, `System.exit()`
und Reflection-Aufrufstellen (`Method.invoke`, `Class.forName`, ausgewählte
`MethodHandles.Lookup`-Methoden) einen Guard-Call gegen `SandboxGuardRegistry.check` ein.
`AgentInstrumentationStrategy` ersetzt `NoOpSandboxStrategy` als Default-Strategie von
`PluginSandbox`, verifiziert bei `activate` die Agent-Voraussetzung
(`PluginSandboxPolicy.requiresApiMediation`) und wirft sonst `SandboxAgentNotActiveException`.
`PluginSandbox.reportViolation` ist jetzt real implementiert (nicht mehr No-Op) und über einen neuen
`violationListener` mit `PluginManager` verdrahtet: ein kategorisierter Verstoß entlädt das Plugin
sofort, markiert seinen `PluginScanResult` als neuen Status `PluginScanStatus.POTENTIAL_ATTACK`,
persistiert den Deaktivierungsgrund und meldet ihn an `ExceptionHandlingStrategy`;
`PluginManager.forceLoad` verweigert einen `POTENTIAL_ATTACK`-Kandidaten. Byte Buddy war bereits
Projektabhängigkeit (kein ASM, keine neue Fremdabhängigkeit); als Host-Start-Voraussetzung wurde
ausschließlich `-javaagent` gewählt (kein dynamisches Attachment). Abweichung vom ursprünglichen
Plan: die vollständige Verstoßbehandlung (Sofort-Entladung, `POTENTIAL_ATTACK`,
`ExceptionHandlingStrategy`-Weiterleitung) wurde bereits in IP-02 statt erst in IP-05 umgesetzt, auf
expliziten Nutzerwunsch; IP-05 baut jetzt nur noch die Anbindung der IP-03-Zeitlimit-Verstöße an
dieselbe, bereits reale `reportViolation`-Logik.

Bewusste Design-Entscheidungen des Nutzers: Bouncy Castle für ASN.1-BER-TLV in IP-04 vorgegeben;
keine OS-Prozess-/Benutzertrennung für IP-04/IP-06 (Erschwerung/Erkennung statt harter Garantie,
um die Nutzung des Plugin-Systems nicht zu verkomplizieren); IP-07 (Byte-Pinning) als vollständige
statt pragmatischer TOCTOU-Behebung gewählt.

IP-08 wurde bei einer Sicherheitsanalyse der Ladepipeline entdeckt (Kollisionsauflösung
berücksichtigt bisher keinen Sicherheits-/Scan-Status - Downgrade-/DoS-Vektor). Klein und
eigenständig, unabhängig von allen anderen Plänen priorisierbar.

IP-08 (Kollisionsauflösung nach Sicherheitsstatus filtern) umgesetzt: `IdCollisionResolver.resolve()`
bildet die konkurrierende Gruppe je Plugin-ID jetzt nur noch aus Kandidaten mit Status `LOADED`;
Kandidaten mit einem anderen Status (z. B. `SECURITY_PROBLEM`) werden unverändert durchgereicht und
können keinen `LOADED`-Kandidaten mehr per Versions-Spoofing verdrängen. Keine Abweichung vom
ursprünglichen Plan.

IP-07 (Checksum-/Signatur-Härtung, Byte-Pinning) umgesetzt: `PinnedPluginContent`
(`Single`/`Multi`) wird einmalig in `PluginScanner.applySecurityCheck` gelesen und über eine neue
`PluginSecurityStrategy.check(result, pinnedContent)`-Überladung geprüft; `PluginScanResult` trägt
das Ergebnis bis zum Laden weiter. `ChecksumSecurityStrategy` und `SignatureSecurityStrategy`
vergleichen Digests jetzt zeitkonstant über eine neue `digestsEqual`-Hilfsfunktion
(`MessageDigest.isEqual`). Ein neues, gemeinsames Modul
`org.pcsoft.framework.pluggiat.classloader.jar` (`resolveJarEntries`) löst ZIP/JAR-Einträge
deterministisch auf (letzter Eintrag gewinnt bei Duplikaten) und wird sowohl von
`SignatureSecurityStrategy` (Auffinden von Manifest-JAR und Checksum-Liste) als auch vom neuen
`PinnedPluginClassLoader` (Klassen-/Ressourcenladen aus gepinnten Bytes) verwendet.
`SignatureSecurityStrategy` prüft zusätzlich `certificate.checkValidity()` und behandelt ein
abgelaufenes/noch nicht gültiges Zertifikat als `Failure`. `PluginLoader` hat eine neue
`load(PinnedPluginContent, ...)`-Überladung; `PluginManager.scan()`/`reactivate()` nutzen
ausschließlich noch den gepinnten Pfad. Abweichung vom ursprünglichen Plan: `PluginSecurity` hat
zusätzlich eine `reevaluateAndPin`-Methode erhalten (statt `reactivate()` selbst neu zu scannen und
zu pinnen), damit die Reaktivierung dieselbe, einmalig gelesene Bytefolge für Prüfung und Laden
verwendet, ohne die bestehenden öffentlichen `evaluate`/`reevaluate`-Signaturen zu brechen; die
kryptografische Signaturprüfung selbst liest weiterhin über `JarInputStream` direkt aus den
gepinnten Bytes (nicht über `resolveJarEntries`), da die JDK-eigene Codesigner-Verifikation an den
`JarInputStream`-Mechanismus gebunden ist - `resolveJarEntries` wird dort nur zum Auffinden der
Manifest-JAR und der Checksum-Liste eingesetzt.
