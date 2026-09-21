# Feature Status: Plugin Management System

Status: IN_PROGRESS

## Implementation Plans

| ID | Implementation Plan | Status |
|----|---------------------|--------|
| IP-01 | Manifest-Schema & Data Classes | COMPLETED |
| IP-02 | Extension-Point-Mechanismus | COMPLETED |
| IP-03 | Plugin-Scanner & Lademodi | COMPLETED |
| IP-04 | Sicherheitskonzept (Strategy-Kette) | NOT_STARTED |
| IP-05 | ClassLoader-Isolation & Abhängigkeitsgraph | NOT_STARTED |
| IP-06 | Lifecycle & Fehlerisolation | NOT_STARTED |
| IP-07 | Orchestrierung & Laufzeit-Runtime | NOT_STARTED |
| IP-08 | Public-Key-Provider-Strategien | NOT_STARTED |

## Overall Progress

37,5% (3/8 Implementierungsplänen abgeschlossen)

## Notes

Feature Plan erstellt. Kein Implementierungsplan wurde bisher gestartet.
IP-04 auf Strategy-Pattern mit Fallback-Kette umgestellt (kein Enum), IP-08 neu für Public-Key-Provider-Strategien (Truststore, direkter Key, OpenPGP RFC 9580) ergänzt.
IP-01 gestartet: Schema/Data-Class-Grundgerüst, YAML-Parsing (Jackson), Icon-Erkennung (ImageIO + SVG), SPDX-Abgleich (statische Ressource) und Maven-Versionsvergleich implementiert; optionale Felder zu `links`/`legal` gruppiert, `$version` bewusst nicht öffentlich exponiert.
IP-02 abgeschlossen: `ExtensionConfiguration<T>`/`@ExtensionPoint` sitzen an der Host-Konfigurationsklasse statt an der Plugin-Implementierung (im Dialog mit dem Nutzer verworfen: Marker-Interface, Annotation an Implementierung); Host registriert Konfigurationsklassen über `ExtensionPointRegistry`, Auflösung/Konfliktbehandlung über `ExtensionAggregator`; `PluginLifecycle`-Interface auf IP-06 verschoben. `ExtensionEntry` (IP-01) um `additionalProperties` erweitert. Neue Abhängigkeit SLF4J (`slf4j-api`/`slf4j-simple`), `licensee` um `allowUrl` für slf4j-api ergänzt.
IP-03 abgeschlossen: Kein `PluginLoadMode`-Enum, `PluginLocation.scanStrategy: PluginScanStrategy` referenziert stattdessen direkt die Strategie-Implementierung (`SingleJarScanStrategy`/`MultiJarWithOwnFolderScanStrategy`/`ZipJarScanStrategy`, Default `ZipJarScanStrategy`); `PluginLocation.type: PluginLocationType` (`BUILTIN`/`EXTERNAL`) statt Bool-Flag; einheitliches Ergebnismodell `PluginScanResult` (`location`, `path`, `manifest`, `status: PluginScanStatus`, `errorMessage`) statt getrennter Kandidaten-/Ergebnisklassen. Platzhalter-Interface `PluginSecurityStrategy` (leer) im neuen Package `security` angelegt, `PluginLocation.securityOverride: List<PluginSecurityStrategy> = emptyList()` nicht-nullable; konkrete Strategien folgen inhaltlich erst in IP-04. Test-Fixtures für JAR/ZIP werden zur Testlaufzeit programmatisch erzeugt statt als Binär-Dateien unter `src/test/resources` eingecheckt. `deleteOnExit`-Registrierung der ZIP-Strategie wird über Reflection auf `java.io.DeleteOnExitHook` verifiziert, dafür `--add-opens java.base/java.io=ALL-UNNAMED` im `test`-Task ergänzt.
