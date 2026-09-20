# Implementierungsplan: Public-Key-Provider-Strategien

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-08.
Voraussetzung: IP-04.

## Aufgabe 1: Strategy-Interface

- [ ] Interface `PublicKeyProviderStrategy` anlegen, liefert `java.security.PublicKey` zu einer Plugin-/Signatur-Kennung
- [ ] Definiertes Fehlschlag-Ergebnis für nicht auflösbaren Key festlegen (kein Absturz)
- [ ] Einbindung in die Signatur-Strategie aus IP-04 als Konstruktor-/Konfigurationsparameter sicherstellen

## Aufgabe 2: Truststore-Provider

- [ ] Implementierung auf Basis eines Java `KeyStore` (Trust-Store-Datei) anlegen
- [ ] Alias-Auflösung des Public Keys aus dem Truststore implementieren
- [ ] Fehlerfall (Alias/Key nicht gefunden) auf definiertes Fehlschlag-Ergebnis abbilden

## Aufgabe 3: Direkter Provider

- [ ] Implementierung anlegen, die einen unmittelbar übergebenen `java.security.PublicKey` liefert

## Aufgabe 4: OpenPGP-Online-Provider

- [ ] Bibliothek für RFC-9580-konformes OpenPGP-Parsing mit dem Nutzer abstimmen (siehe `dependencies.md`)
- [ ] Auflösung des Public Keys über einen HKP-kompatiblen Keyserver implementieren (Referenz `keys.openpgp.org`)
- [ ] Auflösung anhand einer im Manifest bzw. in der Ortskonfiguration hinterlegten Key-ID/Fingerprint umsetzen
- [ ] OpenPGP-Schlüsselmaterial zu einem verwendbaren `java.security.PublicKey` parsen
- [ ] Timeout- und Caching-Verhalten für aufgelöste Keys festlegen und umsetzen
- [ ] Fehlerbehandlung bei nicht erreichbarem Keyserver auf definiertes Fehlschlag-Ergebnis abbilden, kein blockierender Scan-Pfad

## Aufgabe 5: Logging

- [ ] WARN-Log bei nicht auflösbarem Public Key je Provider

## Aufgabe 6: Tests

- [ ] Test Truststore-Provider: bekannter und unbekannter Alias
- [ ] Test Direkter Provider: liefert übergebenen Key unverändert
- [ ] Test OpenPGP-Provider: erfolgreiche Auflösung (gegen Test-/Mock-Keyserver), nicht auflösbare Key-ID, nicht erreichbarer Keyserver
- [ ] Test: Signatur-Strategie aus IP-04 funktioniert mit jeder der drei Provider-Implementierungen

## Aufgabe 7: Dokumentation

- [ ] Seite `docs/docs/host-integration/public-key-providers.md` erstellen
- [ ] `PublicKeyProviderStrategy`-Interface und die drei mitgelieferten Implementierungen erläutern
- [ ] Beispiel für Truststore-, Direkt- und OpenPGP-Konfiguration ergänzen
- [ ] `docs/mkdocs.yml`-Navigation um die Seite ergänzen

## Endzustand

- [ ] Die Signatur-Strategie kann wahlweise mit Truststore-, Direkt- oder OpenPGP-Provider betrieben werden
- [ ] Ein nicht auflösbarer Public Key führt zu einem nachvollziehbaren Fehlschlag, nicht zum Absturz
