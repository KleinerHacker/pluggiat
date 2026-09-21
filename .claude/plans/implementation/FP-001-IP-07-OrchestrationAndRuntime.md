# Implementierungsplan: Orchestrierung & Laufzeit-Runtime

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-07.
Voraussetzung: IP-04, IP-06.

## Aufgabe 1: Öffentliche API

- [ ] Einstiegspunkt zum Start des Gesamtsystems mit Liste von `PluginLocation` sowie der Host-Extension-Point-Registry (Liste von `ExtensionConfiguration`-Klassen aus IP-02) definieren
- [ ] Funktion für gezielten Reload eines einzelnen Plugins bereitstellen
- [ ] Alle Einstiegspunkte als normale, synchron aufrufbare Funktionen gestalten
- [ ] Force-Load-Einstiegspunkt definieren: nimmt Plugin-ID (bzw. zuvor identifiziertes Plugin) entgegen und delegiert an `PluginLoader` (IP-05), unabhängig vom Sicherheitsergebnis
- [ ] Force-Load-Aufruf ergänzt das Scan-/Sicherheitsergebnis um den Hinweis "per Force-Load geladen", ohne das ursprüngliche Sicherheitsergebnis zu verändern

## Aufgabe 2: Gesamtablauf

- [ ] Scanner (IP-03) für alle konfigurierten Orte aufrufen
- [ ] Sicherheitsprüfung (IP-04) auf Scan-Ergebnis anwenden
- [ ] ClassLoader-Erzeugung (IP-05) für sicherheitsgeprüfte Kandidaten anstoßen
- [ ] Manifest-/Extension-Mapping (IP-01, IP-02) durchführen
- [ ] Lifecycle-Aktivierung (IP-06) für geladene Plugins anstoßen

## Aufgabe 3: ID-Kollisionsauflösung

- [ ] Plugins mit gleicher ID aus unterschiedlichen Orten erkennen
- [ ] Höhere Version gewinnt, Log-Warnung ausgeben
- [ ] Bei Versionsgleichheit Checksum vergleichen
- [ ] Bei Checksum-Gleichheit ein Plugin beliebig behalten
- [ ] Bei Checksum-Ungleichheit beide Plugins ablehnen, Sicherheitswarnung ausgeben

## Aufgabe 4: minVersion-Prüfung

- [ ] Host-Version dem Framework beim Start übergeben
- [ ] `minVersion` je Plugin gegen Host-Version vergleichen
- [ ] Plugin mit zu hoher `minVersion` nicht laden, Begründung im Ergebnis vermerken

## Aufgabe 5: Ergebnisstruktur

- [ ] Ergebnis-Modell mit geladenen Plugins, Extensions, Fehlern, Pending-Liste, deaktivierten Plugins zusammenführen
- [ ] Ergebnis nach Abschluss aller Schritte zurückgeben

## Aufgabe 6: Tests

- [ ] Test: vollständiger Durchlauf mit mehreren Orten und gemischten Ergebnissen
- [ ] Test: ID-Kollision wird gemäß Regelwerk aufgelöst
- [ ] Test: Plugin mit zu hoher `minVersion` wird abgelehnt
- [ ] Test: API ist aus separatem Thread aufrufbar, ohne internen Zustand zu verletzen
- [ ] Test: Force-Load-Einstiegspunkt lädt ein an der Sicherheitsprüfung gescheitertes Plugin erfolgreich
- [ ] Test: Force-Load verändert das Ergebnis der ursprünglichen Sicherheitsprüfung im Gesamtergebnis nicht

## Aufgabe 7: Dokumentation

- [ ] Seite `docs/docs/host-integration/setup.md` um öffentliche Start-/Reload-API ergänzen
- [ ] Seite `docs/docs/host-integration/setup.md` um Force-Load-Einstiegspunkt ergänzen, inkl. Hinweis auf Logverantwortung des Hosts
- [ ] Seite `docs/docs/troubleshooting.md` erstellen mit Log-Level-Übersicht
- [ ] ID-Kollision, exklusiven Konflikt und `minVersion`-Ablehnung als Fehlerfälle dokumentieren
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen
- [ ] `docs/docs/index.md` um Verweis auf alle Unterseiten ergänzen

## Endzustand

- [ ] Der Host kann das Gesamtsystem mit einer Liste von Orten starten und ein vollständiges Ergebnis erhalten
- [ ] ID-Kollisionen und `minVersion`-Verstöße werden gemäß Regelwerk aufgelöst
- [ ] Die öffentliche API ist unabhängig vom gewählten Nebenläufigkeitsmodell des Hosts nutzbar
- [ ] Der Host kann für ein einzelnes, an der Sicherheitsprüfung gescheitertes Plugin gezielt einen Force-Load auslösen
