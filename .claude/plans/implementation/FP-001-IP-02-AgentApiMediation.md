# Implementierungsplan: Agent-basierte Bytecode-API-Mediation

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-02

## Aufgabe 1: Java-Agent-Grundgerüst

- [ ] Neues Package `org.pcsoft.framework.pluggiat.sandbox.agent` anlegen
- [ ] `premain`-Einstiegspunkt implementieren
- [ ] `agentmain`-Einstiegspunkt implementieren
- [ ] Manifest-Attribute (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`) in `build.gradle.kts` ergänzen

## Aufgabe 2: Fremdabhängigkeit klären

- [ ] Nutzer nach Bytecode-Umschreibe-Bibliothek fragen (z. B. ASM) gemäß `dependencies.md`
- [ ] Bestätigte Abhängigkeit in `build.gradle.kts` ergänzen

## Aufgabe 3: Bytecode-Instrumentierung

- [ ] `ClassFileTransformer` implementieren, der Plugin-Klassen erkennt
- [ ] Guard-Check vor `java.io.File`-Konstruktoraufrufen einfügen
- [ ] Guard-Check vor `java.net.Socket`-Konstruktoraufrufen einfügen
- [ ] Guard-Check vor `ProcessBuilder.start()` einfügen
- [ ] Guard-Check vor `System.exit()` einfügen

## Aufgabe 4: Reflection-Absicherung

- [ ] Instrumentierung von `Method.invoke` ergänzen
- [ ] Instrumentierung von `Class.forName` ergänzen
- [ ] Instrumentierung von `MethodHandles.Lookup` ergänzen

## Aufgabe 5: Policy-Anbindung

- [ ] API-Kategorien-Feld in `PluginSandboxPolicy` ergänzen (Dateisystem, Netzwerk, Reflection, Prozessstart)
- [ ] `AgentInstrumentationEnforcer`-Klasse erstellen
- [ ] Guard-Checks gegen aktive `PluginSandboxPolicy` prüfen lassen
- [ ] `AgentInstrumentationEnforcer` in `PluginSandbox.activate` einhängen
- [ ] Verstöße an `PluginSandbox.reportViolation` übergeben

## Aufgabe 6: Host-Dokumentation

- [ ] `-javaagent`-Start-Voraussetzung in README/MkDocs dokumentieren
- [ ] `-XX:+EnableDynamicAgentLoading`-Hinweis für JDK 21+ dokumentieren
- [ ] Restlücke bei sehr früher Klasseninitialisierung dokumentieren

## Aufgabe 7: Tests

- [ ] `testing`-Skill laden
- [ ] Testfixture-Plugin mit verbotenem Dateizugriff erstellen
- [ ] Test für erfolgreiche Blockierung von Dateisystemzugriff schreiben
- [ ] Test für erfolgreiche Blockierung von Netzwerkzugriff schreiben
- [ ] Test für Reflection-Umgehungsversuch schreiben

## Aufgabe 8: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
