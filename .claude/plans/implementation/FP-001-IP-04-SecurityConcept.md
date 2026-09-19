# Implementierungsplan: Sicherheitskonzept

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-04.
Voraussetzung: IP-03.

## Aufgabe 1: Sicherheitsstufen-Modell

- [ ] Enum `SecurityLevel` mit `PLAIN`, `MUST_SIGN`, `CHECKSUM` anlegen
- [ ] Default-Zuordnung `MUST_SIGN` für Builtin-Orte festlegen
- [ ] Default-Zuordnung `CHECKSUM` für externe Orte festlegen
- [ ] Ortsbezogenes Override auswerten, hat Vorrang vor Default

## Aufgabe 2: Public-Key-Callback und Signaturprüfung

- [ ] Callback-Interface für Public-Key-Bereitstellung definieren
- [ ] Signaturprüfung für `SINGLE_JAR` (ganze JAR) implementieren
- [ ] Signaturprüfung für `ZIP_JAR` (ganzes ZIP) implementieren
- [ ] Signaturprüfung für `MULTI_JAR_WITH_OWN_FOLDER` (Manifest-JAR) implementieren
- [ ] Checksummenliste der übrigen JARs im Manifest-JAR verifizieren

## Aufgabe 3: Checksum-Callback und Prüfung

- [ ] Callback-Interface für Soll-Checksum-Abfrage definieren
- [ ] Ist-Checksum je Plugin-Kandidat berechnen
- [ ] Callback liefert `NULL`: Status `PENDING_APPROVAL` setzen
- [ ] Ist- und Soll-Checksum weichen ab: Status `PENDING_APPROVAL` setzen
- [ ] Ist- und Soll-Checksum stimmen überein: Plugin als sicherheitsgeprüft markieren

## Aufgabe 4: Freigabe-Mechanismus

- [ ] Callback-Interface zum Persistieren einer akzeptierten Checksum definieren
- [ ] Funktion zum gezielten Auslösen eines Reloads nach Freigabe bereitstellen
- [ ] Sicherstellen, dass Freigabe-Callback nicht blockierend im Scan-Pfad hängt

## Aufgabe 5: Logging

- [ ] WARN-Log bei fehlgeschlagener Signaturprüfung
- [ ] WARN-Log bei Status `PENDING_APPROVAL`

## Aufgabe 6: Tests

- [ ] Test `PLAIN`: kein Check, Plugin wird durchgereicht
- [ ] Test `MUST_SIGN`: gültige und ungültige Signatur je Lademodus
- [ ] Test `CHECKSUM`: Callback liefert `NULL`, Abweichung, Übereinstimmung
- [ ] Test: Override überschreibt Default-Sicherheitsstufe

## Aufgabe 7: Dokumentation

- [ ] Seite `docs/docs/host-integration/security.md` erstellen
- [ ] Sicherheitsstufen `PLAIN`/`MUST_SIGN`/`CHECKSUM` mit Default-Zuordnung erläutern
- [ ] Beispiel für Public-Key-Callback und Signaturprüfung je Lademodus ergänzen
- [ ] Beispiel für Checksum-Callback und Pending-Approval-Ablauf ergänzen
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen

## Endzustand

- [ ] Jedes gescannte Plugin ist eindeutig als ladbar, ungültig oder `PENDING_APPROVAL` klassifiziert
- [ ] Eine spätere Freigabe kann gezielt einen Reload auslösen
