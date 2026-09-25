# Implementierungsplan: Kollisionsauflösung nach Sicherheitsstatus filtern

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-08

## Aufgabe 1: Logikänderung

- [ ] `IdCollisionResolver.resolve()` um Status-Filter erweitern (nur `LOADED` konkurriert)
- [ ] `resolveGroup()` auf gefilterte `LOADED`-Teilmenge anwenden
- [ ] Nicht-`LOADED`-Kandidaten unverändert zurückgeben statt auf `ID_COLLISION` setzen
- [ ] Leere `LOADED`-Teilmenge ohne Kollisionsauflösung behandeln

## Aufgabe 2: Tests

- [ ] `testing`-Skill laden
- [ ] Test für `SECURITY_PROBLEM`-Kandidat mit höherer Version neben `LOADED`-Kandidat schreiben
- [ ] Bestehende `IdCollisionResolverTest`-Fälle auf weiterhin korrektes Verhalten prüfen
- [ ] `IdCollisionAndMinVersionIntegrationTest` um neues Szenario ergänzen

## Aufgabe 3: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
