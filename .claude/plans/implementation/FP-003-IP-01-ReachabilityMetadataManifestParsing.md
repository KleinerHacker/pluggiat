# Plan: FP-003-IP-01 Reachability-Metadata fuer Manifest-Parsing

## Aufgabe 1: Reflektiv genutzte Manifest-Typen ermitteln

- [ ] Jackson-deserialisierte Datenklassen unter `manifest/` auflisten
- [ ] Reflektiv genutzte Konstruktoren, Felder, Enum-Werte je Typ notieren
- [ ] `SpdxLicenses.kt` auf reflektive Nutzung pruefen

## Aufgabe 2: `reflect-config.json` erstellen

- [ ] Verzeichnis `src/main/resources/META-INF/native-image/org.pcsoft.framework/pluggiat/` anlegen
- [ ] `reflect-config.json` mit allen ermittelten Typen befuellen
- [ ] Konstruktor- und Feld-Eintraege je Typ ergaenzen

## Aufgabe 3: Konsistenz-Test schreiben

- [ ] `testing`-Skill vor Testerstellung laden
- [ ] Neuen Test anlegen, der `reflect-config.json` einliest
- [ ] Je gelisteter Klasse `Class.forName`-Existenz pruefen
- [ ] Je gelistetem Feld/Konstruktor Reflection-Existenz pruefen

## Aufgabe 4: Build und Dokumentation pruefen

- [ ] Gradle-Ziel `build` ueber Agenten ausfuehren (volle qualifizierte Pfade)
- [ ] `project-docs`-Skill laden, README/CHANGELOG/KDoc pruefen

## Aufgabe 5: Plan abschliessen

- [ ] Feature-Status-Datei: IP-01 auf `COMPLETED` setzen, Fortschritt neu berechnen
- [ ] `FP-003-IP-01-ReachabilityMetadataManifestParsing.md` mit `git rm` entfernen
