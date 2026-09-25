# Implementierungsplan: Sandbox-Grundmodell, `PluginSandbox`-Fassade und Konfiguration

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-01

## Aufgabe 1: Sandbox-Datenmodelle

- [ ] `PluginSandboxPolicy`-Datenklasse erstellen (API-Kategorien, Zeitlimits, Isolationsstufe)
- [ ] `SandboxViolation`-Datenklasse erstellen, angelehnt an `PluginSecurityCheckResult`
- [ ] `SandboxCheckResult` als Sealed Class (`Success`/`Failure`) erstellen
- [ ] `PluginSandboxEnforcer`-Interface mit einer No-Op-fähigen Methode definieren
- [ ] `NoOpSandboxEnforcer`-Implementierung erstellen

## Aufgabe 2: `PluginSandbox`-Fassade

- [ ] `PluginSandbox`-Klasse in `org.pcsoft.framework.pluggiat.sandbox` erstellen
- [ ] `activate(loadedPlugin, policy)` als No-Op implementieren
- [ ] `runGoverned(pluginId, policy, block)` als direkten Aufruf ohne Governance implementieren
- [ ] `reportViolation(pluginId, violation)` als No-Op mit Log-Eintrag implementieren
- [ ] `deactivate(pluginId)` als No-Op implementieren

## Aufgabe 3: Konfigurationsanbindung

- [ ] `sandboxPolicies`-Map in `PluginManagerConfiguration` ergänzen
- [ ] `sandboxOverride`-Property in `PluginLocationBuilder`/`PluginLocation` ergänzen
- [ ] `defaultSandboxPolicy`-Builder-Funktion in `PluginManagerConfiguration` ergänzen
- [ ] KDoc für alle neuen öffentlichen Typen/Properties ergänzen

## Aufgabe 4: `PluginManager`-Anbindung

- [ ] `val sandbox: PluginSandbox`-Property in `PluginManager` ergänzen
- [ ] `sandbox.activate(...)` nach `loader.load()` und vor Extension-Aktivierung aufrufen
- [ ] Lifecycle-Aufrufe in `unload`/`reload`/`scan` über `sandbox.runGoverned` führen

## Aufgabe 5: Tests

- [ ] `testing`-Skill laden
- [ ] Unit-Test für No-Op-Verhalten von `activate`/`runGoverned`/`reportViolation`/`deactivate` schreiben
- [ ] Test für `sandboxOverride`-Auflösung analog zu `securityOverride` schreiben
- [ ] Test für `sandboxPolicies`-Default-Auflösung pro `PluginLocationType` schreiben

## Aufgabe 6: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent, nicht im Hintergrund)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
