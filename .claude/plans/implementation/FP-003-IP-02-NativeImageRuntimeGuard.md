# Plan: FP-003-IP-02 Laufzeit-Guard gegen nicht unterstuetzte Modi

## Aufgabe 1: GraalVM-SDK-Abhaengigkeit ergaenzen

- [ ] `org.graalvm.sdk:nativeimage` in `build.gradle.kts` als `implementation` ergaenzen
- [ ] Lizenzpruefungs-/CycloneDX-BOM-Gradle-Ziel danach ueber Agenten ausfuehren

## Aufgabe 2: `NativeImageSupport` erstellen

- [ ] Neue Datei `org.pcsoft.framework.pluggiat.NativeImageSupport` anlegen
- [ ] Funktion mit `ImageInfo.inImageCode()`-Aufruf implementieren
- [ ] Ueberschreibbaren internen Test-Seam fuer Simulation ergaenzen

## Aufgabe 3: Exception-Klasse erstellen

- [ ] `NativeImageUnsupportedModeException` mit Plugin-/Strategie-Kontext anlegen

## Aufgabe 4: `forceOnNativeImage`-Feld ergaenzen

- [ ] Feld am Konstruktor von `AgentInstrumentationStrategy` ergaenzen, Default `false`
- [ ] Feld am In-VM-Classloading-Pfad in `PluginLoader` ergaenzen, Default `false`

## Aufgabe 5: Guard-Pruefung einbauen

- [ ] Pruefung in `AgentInstrumentationStrategy.activate` vor bestehender Logik einbauen
- [ ] Pruefung im In-VM-Ladepfad von `PluginLoader`/`PluginClassLoader` einbauen

## Aufgabe 6: Tests schreiben

- [ ] `testing`-Skill vor Testerstellung laden
- [ ] Test: Default wirft Exception unter simuliertem `native-image`
- [ ] Test: `forceOnNativeImage = true` wirft keine Exception
- [ ] Test: normale JVM wirft nie, unabhaengig vom Feldwert

## Aufgabe 7: Build und Dokumentation pruefen

- [ ] Gradle-Ziel `build` ueber Agenten ausfuehren (volle qualifizierte Pfade)
- [ ] `project-docs`-Skill laden, KDoc fuer neue oeffentliche Typen ergaenzen

## Aufgabe 8: Plan abschliessen

- [ ] Feature-Status-Datei: IP-02 auf `COMPLETED` setzen, Fortschritt neu berechnen
- [ ] `FP-003-IP-02-NativeImageRuntimeGuard.md` mit `git rm` entfernen
