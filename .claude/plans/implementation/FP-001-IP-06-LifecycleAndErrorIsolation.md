# Implementierungsplan: Lifecycle & Fehlerisolation

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-06.
Voraussetzung: IP-02, IP-05.

## Aufgabe 1: Lifecycle-Hooks

- [ ] Hook-Methoden `onLoad`/`onEnable`/`onDisable`/`onUnload` am Extension-Basis-Objekt definieren
- [ ] Hooks optional überschreibbar gestalten, Default-Implementierung leer
- [ ] Aufrufreihenfolge `onLoad` vor `onEnable`, `onDisable` vor `onUnload` festlegen

## Aufgabe 2: Enabled/Disabled-Status

- [ ] Callback-Interface zum Lesen des Status je Plugin-ID definieren
- [ ] Callback-Interface zum Schreiben des Status je Plugin-ID definieren
- [ ] Deaktiviertes Plugin bleibt gescannt, Extensions werden nicht aktiviert
- [ ] Deaktivierungsgrund (Nutzer vs. Laufzeitfehler) im Callback unterscheidbar machen

## Aufgabe 3: Laufzeit-Fehlerisolation

- [ ] Extension-Aufrufe mit Fehlerbehandlung umschließen
- [ ] Unbehandelte Exception abfangen, betroffenes Plugin ermitteln
- [ ] `onDisable`/`onUnload` für betroffenes Plugin aufrufen, sofern möglich
- [ ] Status dauerhaft auf deaktiviert setzen über Schreib-Callback
- [ ] Vorfall im Ergebnis-Objekt vermerken

## Aufgabe 4: Logging

- [ ] DEBUG-Log je Lifecycle-Übergang
- [ ] INFO-Log bei dauerhafter Enabled/Disabled-Änderung über Callback
- [ ] ERROR-Log bei Zwangsdeaktivierung durch Laufzeitfehler

## Aufgabe 5: Tests

- [ ] Test: Lifecycle-Hooks werden in korrekter Reihenfolge aufgerufen
- [ ] Test: deaktiviertes Plugin liefert keine aktiven Extensions
- [ ] Test: Laufzeit-Exception führt zu Zwangsdeaktivierung nur des betroffenen Plugins
- [ ] Test: übrige Plugins bleiben nach Zwangsdeaktivierung unbeeinträchtigt

## Aufgabe 6: Dokumentation

- [ ] Seite `docs/docs/plugin-development/lifecycle.md` erstellen, Hooks aus Plugin-Sicht erläutern
- [ ] Seite `docs/docs/host-integration/plugin-lifecycle-management.md` erstellen
- [ ] Enabled/Disabled-Callback und Deaktivierungsgründe für Host beschreiben
- [ ] Verhalten bei Laufzeit-Fehlerisolation für beide Zielgruppen erläutern
- [ ] `docs/mkdocs.yml`-Navigation um beide Seiten ergänzen

## Endzustand

- [ ] Jedes Plugin durchläuft nachvollziehbare Lifecycle-Phasen
- [ ] Enabled/Disabled-Status ist persistent über Callback steuerbar
- [ ] Laufzeitfehler in einer Extension deaktivieren ausschließlich das betroffene Plugin dauerhaft
