# Implementierungsplan: Persistenz-Integritätsschutz

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-06

## Aufgabe 1: Schlüsselverwaltung

- [ ] Schlüsseldatei-Pfad-Konvention festlegen (Sibling zur Persistenzquelle)
- [ ] `SecureRandom`-basierte 256-Bit-Schlüsselerzeugung implementieren
- [ ] Schlüssel bei Erst-Start lesen oder erzeugen

## Aufgabe 2: HMAC-Decorator

- [ ] `IntegrityProtectedPersistenceStrategy`-Klasse erstellen
- [ ] `write()` um HMAC-Berechnung (`HmacSHA256`) erweitern
- [ ] HMAC unter abgeleitetem Zusatzschlüssel in Basis-Strategy speichern
- [ ] `read()` um HMAC-Verifikation erweitern
- [ ] Bei Mismatch `null` zurückgeben und Log-Eintrag schreiben

## Aufgabe 3: Tests

- [ ] `testing`-Skill laden
- [ ] Test für erfolgreiche Verifikation nach normalem Schreiben/Lesen schreiben
- [ ] Test für erkannte Manipulation (verändertem Wert ohne HMAC-Anpassung) schreiben
- [ ] Test für Schlüsselerzeugung bei Erst-Start schreiben
- [ ] Test für Wiederverwendung vorhandenen Schlüssels bei Neustart schreiben

## Aufgabe 4: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
