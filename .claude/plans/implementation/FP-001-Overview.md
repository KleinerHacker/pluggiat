# Übersicht: FP-001 Plugin Management System

- Feature Plan: `.claude/plans/features/FP-001-PluginManagementSystem.md`
- IP-01 Manifest-Schema & Data Classes: `FP-001-IP-01-ManifestSchemaAndDataClasses.md`
- IP-02 Extension-Point-Mechanismus: `FP-001-IP-02-ExtensionPointMechanism.md`
- IP-03 Plugin-Scanner & Lademodi: `FP-001-IP-03-PluginScannerAndLoadModes.md`
- IP-04 Sicherheitskonzept: `FP-001-IP-04-SecurityConcept.md`
- IP-05 ClassLoader-Isolation & Abhängigkeitsgraph: `FP-001-IP-05-ClassLoaderIsolationAndDependencyGraph.md`
- IP-06 Lifecycle & Fehlerisolation: `FP-001-IP-06-LifecycleAndErrorIsolation.md`
- IP-07 Orchestrierung & Laufzeit-Runtime: `FP-001-IP-07-OrchestrationAndRuntime.md`

## Reihenfolge und Voraussetzungen

1. IP-01 — keine Voraussetzung
2. IP-02 — nach IP-01
3. IP-03 — nach IP-01, parallel zu IP-02 möglich
4. IP-04 — nach IP-03
5. IP-05 — nach IP-01 und IP-03, parallel zu IP-04 möglich
6. IP-06 — nach IP-02 und IP-05
7. IP-07 — nach IP-04 und IP-06
