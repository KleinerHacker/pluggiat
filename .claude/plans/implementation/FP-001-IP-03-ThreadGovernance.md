# Implementierungsplan: Thread- und Zeitlimit-Governance

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-03

## Aufgabe 1: Executor-Infrastruktur

- [ ] `ThreadWatchdogStrategy`-Klasse erstellen
- [ ] Dedizierten `ExecutorService` pro Plugin-Id verwalten
- [ ] `ThreadGroup` pro Plugin-Id anlegen

## Aufgabe 2: Timeout-Wrapper

- [ ] `runGoverned` in `PluginSandbox` mit `Future`/Timeout implementieren
- [ ] Zeitlimit-Wert aus `PluginSandboxPolicy` lesen
- [ ] Bei Timeout-Überschreitung Thread als verwaist markieren
- [ ] Bei Timeout-Überschreitung `SandboxViolation` an `reportViolation` übergeben

## Aufgabe 3: Integration Lifecycle-Aufrufe

- [ ] `onEnable`/`onDisable`/`onUnload`-Aufrufe in `PluginManager` über `runGoverned` führen
- [ ] Extension-Methodenaufrufe über `runGoverned` führen, soweit über Aggregator/Proxy möglich

## Aufgabe 4: Aufräumen

- [ ] `deactivate(pluginId)` in `PluginSandbox` um Executor-Shutdown erweitern
- [ ] Verwaiste Threads beim `unload`/`reload` protokollieren

## Aufgabe 5: Tests

- [ ] `testing`-Skill laden
- [ ] Test für Timeout-Erkennung bei blockierendem `onEnable` schreiben
- [ ] Test für normale, schnelle Aufrufe ohne Governance-Overhead schreiben
- [ ] Test für Executor-Shutdown bei `unload`/`reload` schreiben

## Aufgabe 6: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
