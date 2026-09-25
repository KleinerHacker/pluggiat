# Implementierungsplan: Verstoßbehandlung und Beobachtbarkeit

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-05

## Aufgabe 1: `reportViolation`-Implementierung

- [ ] No-Op in `PluginSandbox.reportViolation` durch echte Logik ersetzen
- [ ] Verstoß an `ExceptionHandlingStrategy` weiterleiten
- [ ] Automatische `unload`-Reaktion für betroffenes Plugin auslösen

## Aufgabe 2: Persistenz des Verstoßgrunds

- [ ] Neue Konstante `SANDBOX_VIOLATION_REASON` definieren
- [ ] `DISABLED_REASON_PERSISTENCE_KEY`-Muster für Sandbox-Verstöße wiederverwenden
- [ ] `ENABLED_PERSISTENCE_KEY` bei Verstoß auf `false` setzen

## Aufgabe 3: Tests

- [ ] `testing`-Skill laden
- [ ] Test für automatisches `unload` nach Sandbox-Verstoß schreiben
- [ ] Test für persistierten Deaktivierungsgrund schreiben
- [ ] Test für Konsistenz mit bestehendem `SECURITY_RECHECK_FAILED_REASON`-Muster schreiben

## Aufgabe 4: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
