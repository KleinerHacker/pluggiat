# Implementierungsplan: Prozessisolation für hochriskante Plugins

Feature Plan: [FP-002-PluginRuntimeSandbox](../features/FP-002-PluginRuntimeSandbox.md), IP-04

## Aufgabe 1: Bouncy-Castle-Anbindung

- [ ] Bouncy-Castle-Abhängigkeit (`org.bouncycastle:bcprov-jdk18on`) in `build.gradle.kts` ergänzen
- [ ] Minimalen ASN.1-Typumfang festlegen (`INTEGER`, `BOOLEAN`, `OCTET STRING`, `UTF8String`, `SEQUENCE`, `NULL`)

## Aufgabe 2: IPC-Modul

- [ ] Package `org.pcsoft.framework.pluggiat.sandbox.process.ber` anlegen
- [ ] Encoder-Funktion für Extension-Aufrufe implementieren
- [ ] Decoder-Funktion für Extension-Rückgabewerte implementieren
- [ ] `ServerSocket`-basierten IPC-Server im Subprozess implementieren
- [ ] Socket-basierten IPC-Client im Host implementieren

## Aufgabe 3: Subprozess-Lebenszyklus

- [ ] `ProcessBuilder`-Start des Plugin-Subprozesses implementieren
- [ ] Working-Directory-Isolation für Subprozess konfigurieren
- [ ] Prozessabsturz-Erkennung implementieren
- [ ] Prozessbeendigung bei `unload` implementieren

## Aufgabe 4: Loader-Integration

- [ ] Zweiten Rückgabepfad in `PluginLoader.load()` für Prozessisolation ergänzen
- [ ] `LoadedPlugin`-Äquivalent mit IPC-Proxy-Handle erstellen
- [ ] `ProcessIsolationStrategy`-Klasse erstellen und in `PluginSandbox` einhängen

## Aufgabe 5: Extension-Proxy

- [ ] Dynamischen Proxy pro Extension-Interface implementieren
- [ ] Proxy-Methodenaufrufe auf ASN.1-BER-Nachrichten abbilden
- [ ] `ExtensionAggregator`/`ExtensionPointRegistry` für Proxy-Fall anpassen

## Aufgabe 6: Tests

- [ ] `testing`-Skill laden
- [ ] Testfixture-Plugin für Prozessisolation erstellen
- [ ] Test für erfolgreichen Extension-Aufruf über Prozessgrenze schreiben
- [ ] Test für Prozessabsturz-Behandlung schreiben
- [ ] Test für Timeout bei hängendem Subprozess schreiben

## Aufgabe 7: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
- [ ] `dependencies.md`-Lizenzprüfung für Bouncy Castle durchführen
