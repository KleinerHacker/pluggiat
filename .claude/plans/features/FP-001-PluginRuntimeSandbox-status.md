# Feature Status: Plugin Runtime Sandbox

Status: NOT_STARTED

## Implementation Plans

| ID | Implementation Plan | Status |
|----|---------------------|--------|
| IP-01 | Sandbox-Grundmodell und Konfiguration | NOT_STARTED |
| IP-02 | Agent-basierte Bytecode-API-Mediation | NOT_STARTED |
| IP-03 | Thread- und Zeitlimit-Governance | NOT_STARTED |
| IP-04 | Prozessisolation für hochriskante Plugins | NOT_STARTED |
| IP-05 | Verstoßbehandlung und Beobachtbarkeit | NOT_STARTED |
| IP-06 | Persistenz-Integritätsschutz | COMPLETED |
| IP-07 | Checksum-/Signatur-Härtung (Byte-Pinning) | COMPLETED |
| IP-08 | Kollisionsauflösung nach Sicherheitsstatus filtern | NOT_STARTED |

## Overall Progress

25% (2/8 Implementierungsplänen abgeschlossen)

## Notes

IP-06 (Persistenz-Integritätsschutz) umgesetzt: `IntegrityProtectedPersistenceStrategy` als
Decorator, `SecureRandom`-basierter Schlüssel in separater Schlüsseldatei, HMAC-Schutz je
`write`/`read`. Abweichung vom ursprünglichen Plan: der abgeleitete HMAC-Zusatzschlüssel verwendet
den Suffix `_hmac` statt `.hmac`, da ein Punkt darin die `.`-basierte Flach-Properties-Kodierung von
`FilePersistenceStrategy` (`PROPERTIES`/`XML`) beim Neuladen von der Platte falsch aufteilen würde.

Offene Fragen aus Abschnitt 9 des Feature Plans (fehlender SecurityManager auf JDK 25, Host-seitige
Java-Agent-Voraussetzung für IP-02, Umfang des Bouncy-Castle-Einsatzes für IP-04, Performance-
Overhead, Speicher-/Funktionsumfang-Tradeoffs des Byte-Pinnings bei IP-07) sollten vor Beginn von
IP-02/IP-04/IP-07 mit dem Nutzer geklärt werden.

Bewusste Design-Entscheidungen des Nutzers: Bouncy Castle für ASN.1-BER-TLV in IP-04 vorgegeben;
keine OS-Prozess-/Benutzertrennung für IP-04/IP-06 (Erschwerung/Erkennung statt harter Garantie,
um die Nutzung des Plugin-Systems nicht zu verkomplizieren); IP-07 (Byte-Pinning) als vollständige
statt pragmatischer TOCTOU-Behebung gewählt.

IP-08 wurde bei einer Sicherheitsanalyse der Ladepipeline entdeckt (Kollisionsauflösung
berücksichtigt bisher keinen Sicherheits-/Scan-Status - Downgrade-/DoS-Vektor). Klein und
eigenständig, unabhängig von allen anderen Plänen priorisierbar.

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
