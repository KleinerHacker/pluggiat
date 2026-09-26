# Übersicht: Plugin Runtime Sandbox

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md)

## Implementation Plans

| ID | Name | Dateiname |
|----|------|-----------|
| IP-01 | Sandbox-Grundmodell und Konfiguration | FP-001-IP-01-SandboxFoundation.md |
| IP-02 (COMPLETED) | Agent-basierte Bytecode-API-Mediation | FP-001-IP-02-AgentApiMediation.md (entfernt, siehe Feature Plan) |
| IP-03 | Thread- und Zeitlimit-Governance | FP-001-IP-03-ThreadGovernance.md |
| IP-04 | Prozessisolation für hochriskante Plugins | FP-001-IP-04-ProcessIsolation.md |
| IP-05 | Verstoßbehandlung und Beobachtbarkeit | FP-001-IP-05-ViolationHandling.md |
| IP-06 | Persistenz-Integritätsschutz | FP-001-IP-06-PersistenceIntegrity.md |
| IP-07 | Checksum-/Signatur-Härtung (Byte-Pinning) | FP-001-IP-07-ChecksumHardening.md |
| IP-08 | Kollisionsauflösung nach Sicherheitsstatus filtern | FP-001-IP-08-CollisionResolverFix.md |

Keine dieser Dateien existiert bisher - sie werden erst bei Bedarf als eigene Implementation Plans
angelegt.

## Reihenfolge und Voraussetzungen

1. **IP-01** - keine Voraussetzung, muss zuerst umgesetzt werden.
2. **IP-02** und **IP-03** - jeweils abhängig von IP-01, untereinander unabhängig, parallelisierbar.
3. **IP-04** - abhängig von IP-01, unabhängig von IP-02/IP-03, parallelisierbar zu beiden.
4. **IP-06** - keine Code-Abhängigkeit zu IP-01 (reiner Persistenz-Decorator), vollständig
   eigenständig und jederzeit parallelisierbar zu allen anderen Plänen.
5. **IP-07** - keine Abhängigkeit zu IP-01..IP-06, vollständig eigenständig und jederzeit
   parallelisierbar zu allen anderen Plänen.
6. **IP-08** - keine Abhängigkeit zu IP-01..IP-07, kleinster und am leichtesten unabhängig
   umsetzbarer Plan, jederzeit parallelisierbar.
7. **IP-05** - abhängig von IP-02 UND IP-03, muss nach beiden umgesetzt werden.
