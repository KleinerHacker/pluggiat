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
| IP-06 | Persistenz-Integritätsschutz | NOT_STARTED |
| IP-07 | Checksum-/Signatur-Härtung (Byte-Pinning) | NOT_STARTED |
| IP-08 | Kollisionsauflösung nach Sicherheitsstatus filtern | NOT_STARTED |

## Overall Progress

0%

## Notes

Feature Plan created. No implementation plan has been started yet.

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
