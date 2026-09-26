# Implementierungsplan: Verstoßbehandlung und Beobachtbarkeit

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-05

## Aufgabe 1: Zeitlimit-Verstöße (IP-03) an bestehende Logik anschließen

- [ ] `PluginSandbox.reportViolation` (echte Logik bereits durch IP-02 implementiert) für Timeout-Verstöße ohne `SandboxApiCategory` prüfen
- [ ] Sofort-Entladung, WARN-Log und `ExceptionHandlingStrategy`-Weiterleitung greifen unverändert für Timeout-Verstöße
- [ ] Klarstellen: `PluginScanStatus.POTENTIAL_ATTACK` bleibt reserviert für kategorisierte API-Verstöße aus IP-02, Timeout-Verstöße setzen ihn nicht

## Aufgabe 2: Persistenzgrund für Timeout-Verstöße

- [ ] Neue Konstante `SANDBOX_TIMEOUT_REASON` ergänzend zur IP-02-Konstante für API-Verstöße definieren
- [ ] `DISABLED_REASON_PERSISTENCE_KEY`-Muster für Timeout-Verstöße mit `SANDBOX_TIMEOUT_REASON` befüllen
- [ ] `ENABLED_PERSISTENCE_KEY` bei Timeout-Verstoß auf `false` setzen

## Aufgabe 3: Tests

- [ ] `testing`-Skill laden
- [ ] Test für automatisches `unload` nach Timeout-Verstoß schreiben
- [ ] Test für persistierten Deaktivierungsgrund `SANDBOX_TIMEOUT_REASON` schreiben
- [ ] Test: Timeout-Verstoß setzt `PluginScanStatus` nicht auf `POTENTIAL_ATTACK` (Abgrenzung zu IP-02)
- [ ] Test für Konsistenz beider Verstoßpfade (API-Verstoß vs. Timeout-Verstoß) über `reportViolation`

## Aufgabe 4: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
