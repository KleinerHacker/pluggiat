# Implementierungsplan: ClassLoader-Isolation & Abhängigkeitsgraph

Zugehörig zu Feature Plan FP-001 (`.claude/plans/features/FP-001-PluginManagementSystem.md`), Plan IP-05.
Voraussetzung: IP-01, IP-03.

## Aufgabe 1: Isolierter URLClassLoader

- [ ] Parent-Last-ClassLoader auf Basis `URLClassLoader` implementieren
- [ ] SDK-Whitelist-Konfiguration vom Host entgegennehmen
- [ ] Klassenladeversuch: Whitelist-Pakete an Host-ClassLoader delegieren, sonst eigenen Pfad nutzen
- [ ] ClassLoader je Plugin-Einheit gemäß Lademodus erzeugen (ein/mehrere JARs, entpacktes ZIP)

## Aufgabe 2: ExtensionClassResolver-Implementierung

- [ ] Isolierte Implementierung der Schnittstelle aus IP-02 schreiben
- [ ] Resolver an den jeweiligen Plugin-ClassLoader binden

## Aufgabe 3: Abhängigkeitsgraph

- [ ] Abhängigkeiten aus Manifest (IP-01) einlesen
- [ ] Graph-Datenstruktur zwischen Plugin-ClassLoadern aufbauen
- [ ] Zyklen im Graph erkennen und als Fehler behandeln
- [ ] Ladereihenfolge per topologischer Sortierung ableiten
- [ ] Fehlende `required`-Abhängigkeit: Plugin als ungültig markieren
- [ ] Fehlende `optional`-Abhängigkeit: Plugin trotzdem laden, Abhängigkeit ignorieren

## Aufgabe 4: Sichtbarkeit zwischen Plugins

- [ ] ClassLoader von B kennt ClassLoader von A bei deklarierter Abhängigkeit
- [ ] Sicherstellen, dass B ohne deklarierte Abhängigkeit A nicht sehen kann

## Aufgabe 5: Tests

- [ ] Test: Plugin kann nicht per Reflection auf Host-Klassen außerhalb Whitelist zugreifen
- [ ] Test: Plugin kann Whitelist-Klassen des Hosts laden
- [ ] Test: zyklische Abhängigkeit wird erkannt und abgelehnt
- [ ] Test: fehlende `required`-Abhängigkeit macht Plugin ungültig
- [ ] Test: fehlende `optional`-Abhängigkeit lädt Plugin trotzdem

## Aufgabe 6: Dokumentation

- [ ] Seite `docs/docs/host-integration/sdk-whitelist.md` erstellen, SDK-Whitelist-Konfiguration erläutern
- [ ] Seite `docs/docs/plugin-development/dependencies.md` erstellen
- [ ] Required- vs. Optional-Abhängigkeiten im Manifest erläutern
- [ ] Helper-Klassen-Pattern für optionale Abhängigkeiten mit Beispiel dokumentieren
- [ ] `docs/mkdocs.yml`-Navigation um beide Seiten ergänzen

## Endzustand

- [ ] Plugins sind isoliert geladen und können nur über die Whitelist auf den Host zugreifen
- [ ] Deklarierte Plugin-Abhängigkeiten sind zur Ladezeit korrekt aufgelöst
- [ ] IP-02 kann die hier bereitgestellte `ExtensionClassResolver`-Implementierung nutzen
