# Implementierungsplan: Extension-Point-Mechanismus

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-02.
Voraussetzung: IP-01.

## Aufgabe 1: Basis-Extension-Modell

- [ ] Basis-Datenklasse für einen `extensions`-Eintrag mit Feld `implementation` (FQCN) anlegen
- [ ] Marker-Interface für Extension-Implementierungen definieren
- [ ] Annotation `ExtensionPoint` mit Referenz auf Konfigurationsklasse definieren
- [ ] Annotation um optionales `exclusive`-Flag erweitern

## Aufgabe 2: ExtensionClassResolver-Schnittstelle

- [ ] Interface `ExtensionClassResolver` mit `resolve(fqcn): Class`-Methode definieren
- [ ] Standardimplementierung auf Basis des aufrufenden ClassLoaders schreiben
- [ ] Resolver als austauschbare Abhängigkeit im Decorator vorsehen

## Aufgabe 3: Decorator-Mapping

- [ ] Rohdaten eines `extensions`-Eintrags einlesen
- [ ] Implementierungsklasse über `ExtensionClassResolver` auflösen
- [ ] Annotation an Implementierungsklasse auslesen, zugehörige Konfigurationsklasse ermitteln
- [ ] Restliche YAML-Felder in die Konfigurationsklasse mappen
- [ ] Implementierungsklasse instanzieren, Instanz im `implementation`-Feld ablegen
- [ ] Fehler werfen, wenn Annotation fehlt oder Konfigurationsklasse nicht passt

## Aufgabe 4: Listenaufbau und Konfliktbehandlung

- [ ] Mehrere Extension-Einträge je Key sammeln
- [ ] Bei nicht-exklusivem Key: alle Einträge unverändert in eine Liste übernehmen
- [ ] Bei exklusivem Key: prüfen, ob mehr als ein Plugin den Key befüllt
- [ ] Bei Konflikt: beide beteiligten Plugins als nicht ladbar markieren
- [ ] Log-Warnung mit beiden Plugin-IDs und betroffenem Key ausgeben

## Aufgabe 5: Logging

- [ ] DEBUG-Log bei jeder Instanziierung einer `implementation`-Klasse
- [ ] DEBUG-Log mit Key und Implementierungsklasse je registrierter Extension
- [ ] WARN-Log bei exklusivem Konflikt

## Aufgabe 6: Tests

- [ ] Test: einfache Extension wird korrekt gemappt und instanziiert
- [ ] Test: zwei Plugins mit gleichem, nicht-exklusivem Key ergeben Liste mit beiden Einträgen
- [ ] Test: zwei Plugins mit gleichem exklusiven Key werden beide abgelehnt
- [ ] Test: fehlende Annotation an Implementierungsklasse führt zu Fehler

## Aufgabe 7: Dokumentation

- [ ] Seite `docs/docs/plugin-development/extension-points.md` erstellen
- [ ] Erklären, wie ein Extension-Point (Annotation, Konfigurationsklasse) definiert wird
- [ ] Erklären, wie eine Implementierung für einen Extension-Point geschrieben wird
- [ ] Vollständiges Beispiel (Manifest-Ausschnitt + Kotlin-Implementierung) ergänzen
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen

## Endzustand

- [ ] Ein `extensions`-Eintrag wird typsicher auf Konfigurationsklasse und instanziierte Implementierung gemappt
- [ ] Nicht-exklusive Keys ergeben eine vollständige Liste über Plugins hinweg
- [ ] Exklusive Konflikte führen zu Ablehnung beider Plugins mit Log-Warnung
