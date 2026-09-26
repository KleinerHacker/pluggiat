# Feature Plan: Plugin Management System (COMPLETED)

## 1. Ziel

* Dynamisches Plugin-System für JVM-Anwendungen
* JAR/ZIP-basierte Plugins mit YAML-Manifest, Extension-Points und isolierten ClassLoadern
* Konfigurierbares Sicherheitskonzept pro Plugin-Ort (Signatur, Checksum, unsicher)
* Ladevorgang so gestaltet, dass der Host ihn selbst asynchron/nicht-blockierend betreiben kann
* Lifecycle-Verwaltung inkl. Enable/Disable und Laufzeit-Fehlerisolation je Plugin

## 2. Ausgangslage

* Repository `pluggiat` war ein leeres Kotlin/Gradle-Grundgerüst ohne Quellcode
* Single-Module-Setup (`build.gradle.kts`, `settings.gradle.kts`), Gruppe `org.pcsoft.framework`
* Kotlin 2.4.20, JVM Toolchain 25, Dokka, Kover, License-Report, CycloneDX-BOM vorhanden
* Projekt blieb für dieses Feature als Single-Module-Setup bestehen, keine Aufteilung in z. B. `api`/`core`

## 3. Erreichter Zustand

* Plugins liegen als JAR/ZIP an konfigurierbaren Orten und werden zur Laufzeit gescannt
* Jedes Plugin besitzt ein Manifest `META-INF/plugin.yml` bzw. `plugin.yaml`, validiert gegen ein synchronisiertes JSON-Schema und gemappt auf synchronisierte Data Classes
* Extension-Points werden generisch über `extensions.<key>[]` deklariert; der Host definiert jeden Extension-Point über eine `@ExtensionPoint`-annotierte Konfigurationsklasse (Key, `exclusive`-Flag), die `ExtensionConfiguration<T>` implementiert; ein Decorator mappt die YAML-Rohdaten inkl. instanziierter Implementierung darauf; Plugin-Entwickler kennen dabei ausschließlich das Host-Plugin-API-Interface `T`
* Scanner unterstützt drei Lademodi (`SINGLE_JAR`, `MULTI_JAR_WITH_OWN_FOLDER`, `ZIP_JAR`, Default `ZIP_JAR`) und liefert gültige sowie ungültige Plugins mit Fehlermeldungen zurück
* Jeder Plugin-Ort ist als `BUILTIN` oder extern klassifiziert und besitzt ein Sicherheitskonzept in Form einer geordneten, frei erweiterbaren Fallback-Kette von `PluginSecurityStrategy`-Strategien, mit ortsbezogenem Override der gesamten Kette; mitgelieferte Strategien: kein Check, Signatur, Checksum
* Die Signatur-Strategie bezieht den zu prüfenden Public Key über eine eigene, austauschbare `PublicKeyProviderStrategy`: Truststore (Java `KeyStore`), direkter `java.security.PublicKey`, Online-Plattform OpenPGP (RFC 9580, z. B. `keys.openpgp.org`)
* Der Host kann für ein einzelnes Plugin explizit einen Force-Load anfordern, der eine fehlgeschlagene Sicherheitsprüfung gezielt und nachvollziehbar protokolliert übergeht
* Plugins werden über isolierte `URLClassLoader` geladen, die den Zugriff auf den Code der Host-Anwendung per Reflection unterbinden, aber gezielt eine vom Host konfigurierte SDK-Whitelist freigeben
* Plugin-Abhängigkeiten (`required`/`optional`) bilden einen ClassLoader-Abhängigkeitsgraphen zur gezielten Klassensichtbarkeit zwischen Plugins
* ID-Kollisionen zwischen Orten werden über Versionsvergleich (Maven-Schema) und nachgelagerte Checksum-Prüfung aufgelöst
* Jedes Plugin durchläuft definierte Lifecycle-Phasen (`onLoad`/`onEnable`/`onDisable`/`onUnload`); ein Deaktivieren schließt immer das Verwerfen des Plugin-ClassLoaders ein, eine Reaktivierung erfordert einen vollständigen Neu-Ladevorgang inkl. erneuter Sicherheitsprüfung
* Jedes Plugin besitzt einen persistenten Enabled/Disabled-Status, verwaltet über eine austauschbare `PluginPersistenceStrategy` (mitgelieferte Implementierungen: kein Persistieren, Host-Callback, Datei, Datenbank via JDBC)
* Eine zur Laufzeit von einer Extension nach außen dringende Exception wird über eine konfigurierbare `ExceptionHandlingStrategy` (IGNORE/UNLOAD/CRASH je Exception-Typ, mit Standard-Matrix) behandelt; UNLOAD deaktiviert dauerhaft genau dieses eine Plugin, nicht die gesamte Anwendung
* Jede an den Host ausgelieferte Extension-Instanz ist ein Proxy (JDK-Proxy für Interfaces, ByteBuddy-Subklasse für offene Klassen) über das Host-Plugin-API; dadurch dringen aus einem Extension-Aufruf ausschließlich `PluginExecutionException`/`PluginFatalException` nach außen, nie eine rohe Plugin-Exception
* Ein zentraler, per Kotlin-Builder konfigurierbarer Einstiegspunkt `PluginManager` (Root-Paket) bündelt die host-weite Konfiguration und liefert vorkonfigurierte Kernkomponenten (`PluginScanner`, `PluginSecurity`, `PluginLoader`)

## 4. Anforderungen

### Funktionale Anforderungen

* Manifest-Pflichtfelder: `$version`, `id`, `name`, `version`, `minVersion`, `icon`
* Manifest-Optionalfelder: `description`, `author` (`name` Pflicht, `mail` optional), `documentationUrl`, `sourceCodeUrl`, `copyright`, `license`
* `icon`: Base64-kodiert, Format per Magic-Bytes erkannt (SVG, PNG, JPG, ...)
* `license`: freier String, optional gegen SPDX-Identifier-Liste abgeglichen
* `version` und `minVersion`: Maven-Versionsschema, vergleichbar
* Plugin-Abhängigkeiten im Manifest, je Abhängigkeit `required` oder `optional`
* Scanner-Ergebnis enthält gültige und ungültige Plugins (mit Fehlermeldungen) sowie Plugins im Status `PENDING_APPROVAL`
* Extension-Points ohne `exclusive`-Flag: mehrere Einträge gleichen Keys aus verschiedenen Plugins werden einfach zu einer Liste zusammengefügt
* Extension-Points mit `exclusive`-Flag: Befüllen zwei Plugins denselben Key, werden **beide Plugins vollständig** nicht geladen, mit Log-Warnung inkl. beider Plugin-IDs und Key
* ID-Kollision zwischen Orten: Log-Warnung, höhere Version gewinnt; bei Versionsgleichheit Checksum-Vergleich; bei Checksum-Gleichheit beliebige Auswahl, bei Checksum-Ungleichheit Sicherheitswarnung und keines der beiden laden
* `minVersion`-Prüfung gegen die Version der Host-Software, zu neue Plugins werden nicht geladen
* Persistenter Enabled/Disabled-Status je Plugin-ID; Status-Prüfung erfolgt VOR jedem Klassenladen der Extension, ein deaktiviertes Plugin bleibt gescannt, aber keine seiner Extension-Klassen wird geladen/instanziiert
* Laufzeit-Fehlerisolation über eine konfigurierbare `ExceptionHandlingStrategy`; `UNLOAD` deaktiviert das betroffene Plugin dauerhaft und muss manuell reaktiviert werden
* Force-Load: der Framework-Nutzer kann pro Plugin explizit das Laden trotz fehlgeschlagener Sicherheitsprüfung erzwingen; der Vorgang wird mit Plugin-ID und Grund der ursprünglich fehlgeschlagenen Prüfung protokolliert
* Reaktivierung eines deaktivierten Plugins löst zwingend einen erneuten Sicherheitsprüfungslauf vor dem eigentlichen Neu-Laden aus

### Technische Anforderungen

* Kotlin, Gradle (siehe `development.md`)
* Manifest-Parsing über YAML mit synchronisiertem JSON-Schema und Data Classes; beide werden manuell parallel gepflegt, Konsistenz wird über Tests abgesichert
* ClassLoader-Isolation: Parent-Last-Strategie gegenüber der Host-Anwendung mit gezielt freigegebener SDK-Schicht, vom Host als Konfiguration übergeben
* Sicherheitskonzept als Strategy-Pattern: `PluginSecurityStrategy`-Interface, ausschließlich über neue Implementierungen erweiterbar, kein Enum; die Kette wird strikt in Konfigurationsreihenfolge geprüft, ein Sicherheitsproblem wird erst gemeldet, wenn ALLE Strategien der Kette fehlgeschlagen sind
* Das Framework legt sich nicht auf eine konkrete Async-API fest; alle Einstiegspunkte sind so gestaltet, dass der Host sie in dem von ihm gewählten Nebenläufigkeitsmodell blockierend oder nicht-blockierend aufrufen kann
* Persistenz-Strategie als Strategy-Pattern (`PluginPersistenceStrategy`), Exception-Handling als Strategy-Pattern (`ExceptionHandlingStrategy`), Durchsetzung der Fehlerisolation über einen Proxy
* Zentraler Einstiegspunkt `PluginManager` (Root-Paket) mit Kotlin-Builder-DSL
* Durchgängiges Logging des Scan-/Ladeprozesses mit den Log-Levels `INFO`/`WARN`/`DEBUG`/`ERROR`

## 5. Architektur

* Komponenten:
    * **Manifest-Modul**: YAML-Parsing, JSON-Schema, Data Classes, Validierung
    * **Extension-Modul**: `ExtensionConfiguration<T>`-Interface, `@ExtensionPoint`-Annotation, Host-Registry, Decorator-Mapping mit Proxy-Auslieferung
    * **Scanner-Modul**: Orte-Konfiguration, Scan-Strategien je Lademodus, Ergebnis-Modell
    * **Security-Modul**: `PluginSecurityStrategy`-Interface, Fallback-Ketten-Auswertung, mitgelieferte Strategien
    * **Public-Key-Provider-Modul**: `PublicKeyProviderStrategy`-Interface, mitgelieferte Implementierungen
    * **ClassLoader-Modul**: `PluginLoader`-Klasse, vom Host konfigurierte SDK-Whitelist, Abhängigkeitsgraph zwischen Plugin-ClassLoadern
    * **Persistence-Modul**: `PluginPersistenceStrategy`-Interface, mitgelieferte Implementierungen
    * **Lifecycle-Modul**: Lifecycle-Hooks, persistenter Enabled/Disabled-Status, Laufzeit-Fehlerisolation, Proxy-Durchsetzung
    * **PluginManager-Modul**: zentraler, per Kotlin-Builder konfigurierbarer Einstiegspunkt im Root-Paket
    * **Orchestrierung/Runtime-Modul**: Zusammenspiel Scanner → Security → ClassLoader → Manifest/Extension-Mapping → Lifecycle, host-gesteuerte Nebenläufigkeit, ID-Kollisionsauflösung, `minVersion`-Prüfung, Force-Load-Einstiegspunkt
* Datenfluss: Plugin-Orte → Scanner → Security-Prüfung → ClassLoader-Erzeugung → Manifest-Deserialisierung + Validierung → Enabled/Disabled-Status-Prüfung → Extension-Decorator-Mapping mit Proxy-Auslieferung → Lifecycle-Aktivierung → Ergebnis
* Externe Schnittstellen (durch Framework-Nutzer bereitzustellen): Public-Key-Callback, Soll-Checksum-Callback, `PluginPersistenceStrategy`, `ExceptionHandlingStrategy` (optional, sonst Default-Matrix), SDK-Whitelist-Konfiguration, Force-Load-Aufruf je Plugin
* Persistenz: über die austauschbare `PluginPersistenceStrategy` (Default `NoPersistenceStrategy`); entpackte ZIP-Plugins liegen im Temp-Verzeichnis und werden per `deleteOnExit` entfernt
* MkDocs-Struktur (Zielgruppentrennung Plugin-Entwickler/Host-Integratoren):
    * `index.md` — Übersicht/Kernkonzepte
    * `plugin-development/manifest.md` — Manifest-Felder + Beispiel
    * `plugin-development/extension-points.md` — Extension-Point erstellen/konsumieren + Beispiel
    * `plugin-development/dependencies.md` — required/optional Abhängigkeiten, Helper-Klassen-Pattern
    * `plugin-development/lifecycle.md` — Lifecycle-Hooks aus Plugin-Sicht
    * `plugin-development/error-handling.md` — empfohlene Exceptions, Standard-Matrix, Proxy-Design-Regel, Debugging-Hinweise
    * `host-integration/setup.md` — Plugin-Orte, Lademodi, Start-/Reload-API
    * `host-integration/security.md` — Sicherheitskonzepte, Strategy-Kette + Beispiel
    * `host-integration/public-key-providers.md` — Public-Key-Provider-Strategien + Beispiel
    * `host-integration/sdk-whitelist.md` — SDK-Whitelist-Konfiguration des Hosts
    * `host-integration/plugin-lifecycle-management.md` — Enabled/Disabled-Status, Deaktivierungsgründe, Reaktivierungsablauf
    * `host-integration/persistence.md` — `PluginPersistenceStrategy`, Implementierungen, Produktiv-Empfehlung
    * `host-integration/plugin-manager.md` — `PluginManager`, Builder-DSL, vorkonfigurierte Instanzen
    * `troubleshooting.md` — Log-Level-Übersicht, Fehlerfälle (ID-Kollision, exklusiver Konflikt, minVersion)

## 6. Kriterien für den Feature-Abschluss

* Ein Plugin mit gültigem Manifest wird über alle drei Lademodi korrekt erkannt, geladen und seine Extensions stehen typisiert mit instanziierter Implementierung zur Verfügung
* Ein Plugin mit ungültigem Manifest oder fehlgeschlagener Sicherheitsprüfung wird nicht geladen und erscheint mit nachvollziehbarer Fehlermeldung im Scan-Ergebnis
* Ein Plugin mit geänderter Checksum landet im Status `PENDING_APPROVAL` und wird erst nach Freigabe geladen, ohne dass das Framework selbst den Scan blockiert
* Zwei Plugin-Orte mit kollidierender ID werden gemäß der definierten Versions-/Checksum-Regeln eindeutig aufgelöst
* Ein Plugin-Ort kann eine geordnete Kette aus mehreren Sicherheitsstrategien konfigurieren; ein Plugin gilt als sicherheitsgeprüft, sobald eine Strategie der Kette erfolgreich ist, und wird erst abgelehnt, wenn ALLE Strategien der Kette fehlschlagen
* Neue Sicherheitsstrategien lassen sich als reine Implementierung des `PluginSecurityStrategy`-Interfaces ergänzen, ohne Framework-Code zu ändern (kein Enum)
* Die Signatur-Strategie kann wahlweise mit Truststore-, Direkt- oder OpenPGP-Public-Key-Provider betrieben werden; ein nicht auflösbarer OpenPGP-Key führt zu einem nachvollziehbaren Fehlschlag, nicht zum Absturz
* Ein Plugin kann nicht per Reflection auf Host-internen Code zugreifen, wohl aber auf die vom Host konfigurierte SDK-Whitelist
* Plugin-Abhängigkeiten (`required`/`optional`) werden beim Laden korrekt berücksichtigt, inklusive Zyklenerkennung
* Ein Plugin mit `minVersion` über der aktuellen Host-Version wird nicht geladen
* Ein über `PluginPersistenceStrategy` dauerhaft deaktiviertes Plugin lädt keine Extension-Klassen und besitzt keinen aktiven ClassLoader, bleibt aber im Scan-Ergebnis sichtbar
* Eine aus einem Extension-Aufruf nach außen dringende Exception wird gemäß `ExceptionHandlingStrategy` behandelt; `UNLOAD` deaktiviert automatisch nur das betroffene Plugin dauerhaft (muss manuell reaktiviert werden), ohne die übrige Anwendung zu beeinträchtigen
* Jede an den Host ausgelieferte Extension-Instanz ist eine Proxy-Instanz; aus dem Host-Plugin-API dringen ausschließlich `PluginExecutionException`/`PluginFatalException` nach außen, nie eine rohe Plugin-Exception
* Eine Reaktivierung eines deaktivierten Plugins führt nur nach erneut erfolgreicher Sicherheitsprüfung zu einem tatsächlichen Neu-Laden
* Befüllen zwei Plugins denselben exklusiven Extension-Point, werden beide vollständig nicht geladen und eine nachvollziehbare Log-Warnung ausgegeben
* Die öffentliche API des Frameworks lässt sich sowohl blockierend als auch aus einem vom Host gewählten nebenläufigen Kontext heraus verwenden
* Ein Plugin mit fehlgeschlagener Sicherheitsprüfung kann über einen expliziten Force-Load-Aufruf des Hosts dennoch geladen werden, wobei der Vorgang nachvollziehbar protokolliert wird
* `PluginManager` liefert per Kotlin-Builder-DSL vorkonfigurierte `scanner`/`security`/`loader`-Instanzen aus einer einzigen host-weiten Konfiguration
