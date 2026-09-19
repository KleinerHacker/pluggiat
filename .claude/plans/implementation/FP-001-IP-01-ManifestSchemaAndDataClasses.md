# Implementierungsplan: Manifest-Schema & Data Classes

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-01.

## Aufgabe 1: JSON-Schema-Grundgerüst

- [ ] JSON-Schema-Datei mit Pflichtfeldern `$version`, `id`, `name`, `version`, `minVersion`, `icon` anlegen
- [ ] Optionalfelder `description`, `author`, `documentationUrl`, `sourceCodeUrl`, `copyright`, `license` ergänzen
- [ ] `author`-Objekt mit Pflichtfeld `name` und optionalem `mail` definieren
- [ ] `extensions`-Grundgerüst mit Pflichtfeld `implementation` je Eintrag definieren
- [ ] `dependencies`-Grundgerüst mit `id` und `required`/`optional`-Flag definieren
- [ ] Schema als Ressource für spätere Validierung und Tests ablegen

## Aufgabe 2: Kotlin Data Classes

- [ ] Data Class `PluginManifest` mit allen Pflicht-/Optionalfeldern anlegen
- [ ] Data Class `Author` anlegen
- [ ] Data Class `ExtensionEntry` mit `implementation`-Feld anlegen
- [ ] Data Class `PluginDependency` mit `id` und `required`-Flag anlegen
- [ ] Data Classes 1:1 den Feldern des Schemas zuordnen

## Aufgabe 3: YAML-Parsing

- [ ] YAML-Bibliothek als Dependency ergänzen (Lizenz vorab prüfen)
- [ ] Parser für `META-INF/plugin.yml` implementieren
- [ ] Parser für `META-INF/plugin.yaml` implementieren
- [ ] Validierung gegen JSON-Schema vor dem Mapping durchführen
- [ ] Aussagekräftige Fehlermeldung bei Schema-Verstoß erzeugen

## Aufgabe 4: Icon-Erkennung

- [ ] Base64-Decoder für `icon`-Feld implementieren
- [ ] Magic-Byte-Erkennung für PNG implementieren
- [ ] Magic-Byte-Erkennung für JPG implementieren
- [ ] Erkennung von SVG (XML-basiert) implementieren
- [ ] Fehler bei nicht erkennbarem Format werfen

## Aufgabe 5: Lizenz- und Versionsvergleich

- [ ] SPDX-Identifier-Liste einbinden
- [ ] `license`-Feld optional gegen SPDX-Liste abgleichen, kein Fehler bei Nichttreffer
- [ ] Maven-Versionsvergleich-Utility implementieren
- [ ] Vergleichsfunktion für `version` und `minVersion` mit Tests absichern

## Aufgabe 6: Schema-Synchronisations-Tests

- [ ] Test: jedes Schema-Feld hat ein Pendant in den Data Classes
- [ ] Test: jedes Data-Class-Feld hat ein Pendant im Schema
- [ ] Test schlägt fehl, wenn ein Feld nur einseitig ergänzt wird

## Aufgabe 7: Dokumentation

- [ ] Seite `docs/docs/index.md` mit Projektübersicht und Kernkonzepten anlegen
- [ ] Seite `docs/docs/plugin-development/manifest.md` mit allen Manifest-Feldern und Beispiel-YAML erstellen
- [ ] `docs/mkdocs.yml`-Navigation um beide Seiten ergänzen

## Endzustand

- [ ] Ein Manifest-YAML kann geladen, validiert und in Data Classes gemappt werden
- [ ] Icon-Format und Lizenz-Identifier werden korrekt erkannt
- [ ] `version`/`minVersion` sind nach Maven-Schema vergleichbar
