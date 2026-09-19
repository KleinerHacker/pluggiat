# Implementierungsplan: Plugin-Scanner & Lademodi

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-03.
Voraussetzung: IP-01.

## Aufgabe 1: Orte-Konfiguration

- [ ] Datenklasse `PluginLocation` mit Pfad, Lademodus, Builtin/Extern-Flag anlegen
- [ ] Optionales Sicherheits-Override-Feld ergänzen
- [ ] Default-Lademodus `ZIP_JAR` festlegen

## Aufgabe 2: Scan-Strategie SINGLE_JAR

- [ ] Verzeichnis nach JAR-Dateien durchsuchen
- [ ] Jede JAR als eigenständigen Plugin-Kandidaten behandeln
- [ ] Manifest aus JAR-Root `META-INF` lesen

## Aufgabe 3: Scan-Strategie MULTI_JAR_WITH_OWN_FOLDER

- [ ] Unterordner im Plugin-Ort ermitteln
- [ ] Alle JARs eines Unterordners einem Plugin-Kandidaten zuordnen
- [ ] Manifest-JAR anhand vorhandenem `META-INF/plugin.yml`/`.yaml` identifizieren

## Aufgabe 4: Scan-Strategie ZIP_JAR

- [ ] ZIP-Dateien im Plugin-Ort ermitteln
- [ ] ZIP in temporäres Verzeichnis entpacken
- [ ] Temporäres Verzeichnis per `deleteOnExit` registrieren
- [ ] Entpackten Inhalt als Plugin-Kandidat behandeln

## Aufgabe 5: Validierung und Fehlerergebnis

- [ ] Für jeden Kandidaten Manifest laden (IP-01 nutzen)
- [ ] Kandidat ohne lesbares/valides Manifest als ungültig markieren
- [ ] Fehlermeldung mit Grund je ungültigem Kandidaten erzeugen
- [ ] Ergebnis-Modell mit getrennten Listen gültig/ungültig aufbauen

## Aufgabe 6: Logging

- [ ] INFO-Log beim Start des Scans je Ort mit Modus und Builtin/Extern-Flag
- [ ] WARN-Log je ungültigem Plugin mit Begründung

## Aufgabe 7: Tests

- [ ] Test je Lademodus mit gültigem Plugin-Beispiel
- [ ] Test je Lademodus mit ungültigem Plugin-Beispiel
- [ ] Test: `deleteOnExit`-Registrierung bei `ZIP_JAR` erfolgt

## Aufgabe 8: Dokumentation

- [ ] Seite `docs/docs/host-integration/setup.md` erstellen
- [ ] Konfiguration von Plugin-Orten, Lademodus und Builtin/Extern-Flag beschreiben
- [ ] Die drei Lademodi mit Verzeichnisbeispiel je Modus erläutern
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen

## Endzustand

- [ ] Für eine Liste konfigurierter Orte liefert der Scanner gültige und ungültige Plugin-Kandidaten
- [ ] Alle drei Lademodi funktionieren unabhängig von Sicherheit und ClassLoader
- [ ] Entpackte ZIP-Inhalte werden automatisch bereinigt
