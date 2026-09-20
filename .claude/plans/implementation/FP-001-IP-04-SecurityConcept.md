# Implementierungsplan: Sicherheitskonzept (Strategy-Kette)

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-04.
Voraussetzung: IP-03.

## Aufgabe 1: Strategy-Interface und Ketten-Modell

- [ ] Interface `PluginSecurityStrategy` mit Prüf-Operation je Plugin-Kandidat anlegen
- [ ] Ergebnis-Typ mit Zuständen Erfolg / Fehlschlag / `PENDING_APPROVAL` definieren
- [ ] Plugin-Ort erhält geordnete, nicht-leere Liste von `PluginSecurityStrategy` (statt Enum)
- [ ] Default-Ketten für `BUILTIN`- und externe Orte festlegen
- [ ] Ortsbezogenes Override der gesamten Kette auswerten, hat Vorrang vor Default

## Aufgabe 2: Ketten-Auswertungslogik

- [ ] Kette in konfigurierter Reihenfolge prüfen, erster Erfolg beendet Prüfung positiv
- [ ] Gesamtfehlschlag nur melden, wenn ALLE Strategien der Kette fehlgeschlagen sind
- [ ] Verrechnung von `PENDING_APPROVAL` innerhalb der Kette festlegen und umsetzen

## Aufgabe 3: Strategie "kein Check"

- [ ] `PluginSecurityStrategy`-Implementierung ohne Prüfung (ehemals `PLAIN`) anlegen, Plugin wird durchgereicht

## Aufgabe 4: Signatur-Strategie

- [ ] `PluginSecurityStrategy`-Implementierung für Signaturprüfung (ehemals `MUST_SIGN`) anlegen
- [ ] Injektionspunkt für `PublicKeyProviderStrategy` (Konstruktor-/Konfigurationsparameter) vorsehen, Schnittstelle aus IP-08
- [ ] Signaturprüfung für `SINGLE_JAR` (ganze JAR) implementieren
- [ ] Signaturprüfung für `ZIP_JAR` (ganzes ZIP) implementieren
- [ ] Signaturprüfung für `MULTI_JAR_WITH_OWN_FOLDER` (Manifest-JAR) implementieren
- [ ] Checksummenliste der übrigen JARs im Manifest-JAR verifizieren

## Aufgabe 5: Checksum-Strategie

- [ ] `PluginSecurityStrategy`-Implementierung für Checksum-Prüfung (ehemals `CHECKSUM`) anlegen
- [ ] Callback-Interface für Soll-Checksum-Abfrage definieren
- [ ] Ist-Checksum je Plugin-Kandidat berechnen
- [ ] Callback liefert `NULL`: Status `PENDING_APPROVAL` setzen
- [ ] Ist- und Soll-Checksum weichen ab: Status `PENDING_APPROVAL` setzen
- [ ] Ist- und Soll-Checksum stimmen überein: Strategie als erfolgreich markieren

## Aufgabe 6: Freigabe-Mechanismus

- [ ] Callback-Interface zum Persistieren einer akzeptierten Checksum definieren
- [ ] Funktion zum gezielten Auslösen eines Reloads nach Freigabe bereitstellen
- [ ] Sicherstellen, dass Freigabe-Callback nicht blockierend im Scan-Pfad hängt

## Aufgabe 7: Logging

- [ ] WARN-Log bei fehlgeschlagener Einzelstrategie
- [ ] WARN-Log bei Gesamtfehlschlag der Kette (alle Strategien fehlgeschlagen)
- [ ] WARN-Log bei Status `PENDING_APPROVAL`

## Aufgabe 8: Tests

- [ ] Test "kein Check": keine Prüfung, Plugin wird durchgereicht
- [ ] Test Signatur-Strategie: gültige und ungültige Signatur je Lademodus
- [ ] Test Checksum-Strategie: Callback liefert `NULL`, Abweichung, Übereinstimmung
- [ ] Test: Override überschreibt Default-Strategie-Kette
- [ ] Test: Fallback-Kette mit mehreren Strategien — erste erfolgreiche Strategie beendet Prüfung positiv
- [ ] Test: Fallback-Kette meldet Sicherheitsproblem erst, wenn ALLE Strategien fehlgeschlagen sind
- [ ] Test: eigene, framework-fremde `PluginSecurityStrategy`-Implementierung lässt sich in die Kette einhängen

## Aufgabe 9: Dokumentation

- [ ] Seite `docs/docs/host-integration/security.md` erstellen
- [ ] `PluginSecurityStrategy`-Interface, Fallback-Kette und mitgelieferte Strategien erläutern
- [ ] Beispiel für eigene Strategie-Implementierung ergänzen
- [ ] Beispiel für Checksum-Callback und Pending-Approval-Ablauf ergänzen
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen

## Endzustand

- [ ] Jedes gescannte Plugin ist eindeutig als ladbar, ungültig oder `PENDING_APPROVAL` klassifiziert
- [ ] Ein Sicherheitsproblem wird erst gemeldet, wenn alle konfigurierten Strategien der Kette fehlgeschlagen sind
- [ ] Eine spätere Freigabe kann gezielt einen Reload auslösen
