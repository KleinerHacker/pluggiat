# Feature Plan: Plugin Management System

## 1. Ziel

* Dynamisches Plugin-System für JVM-Anwendungen
* JAR/ZIP-basierte Plugins mit YAML-Manifest, Extension-Points und isolierten ClassLoadern
* Konfigurierbares Sicherheitskonzept pro Plugin-Ort (Signatur, Checksum, unsicher)
* Ladevorgang so gestaltet, dass der Host ihn selbst asynchron/nicht-blockierend betreiben kann
* Lifecycle-Verwaltung inkl. Enable/Disable und Laufzeit-Fehlerisolation je Plugin

## 2. Ist-Zustand

* Repository `pluggiat` ist ein leeres Kotlin/Gradle-Grundgerüst ohne Quellcode
* Single-Module-Setup (`build.gradle.kts`, `settings.gradle.kts`), Gruppe `org.pcsoft.framework`
* Kotlin 2.4.20, JVM Toolchain 25, Dokka, Kover, License-Report, CycloneDX-BOM vorhanden
* Keine bestehenden Konventionen zu Modulen, Paketen oder Architektur vorhanden
* Projekt bleibt für dieses Feature als Single-Module-Setup bestehen, keine Aufteilung in z. B. `api`/`core` erforderlich

## 3. Soll-Zustand

* Plugins liegen als JAR/ZIP an konfigurierbaren Orten und werden zur Laufzeit gescannt
* Jedes Plugin besitzt ein Manifest `META-INF/plugin.yml` bzw. `plugin.yaml`, validiert gegen ein synchronisiertes JSON-Schema und gemappt auf synchronisierte Data Classes; Schema und Data Classes werden manuell parallel gepflegt
* Extension-Points werden generisch über `extensions.<key>[]` deklariert; der Host definiert jeden Extension-Point über eine `@ExtensionPoint`-annotierte Konfigurationsklasse (Key, `exclusive`-Flag), die `ExtensionConfiguration<T>` implementiert, registriert diese beim Framework, und ein Decorator mappt die YAML-Rohdaten inkl. instanziierter Implementierung (Factory/Singleton) darauf; Plugin-Entwickler kennen dabei ausschließlich das Host-Plugin-API-Interface `T`, keine Framework-Annotation
* Scanner unterstützt drei Lademodi (`SINGLE_JAR`, `MULTI_JAR_WITH_OWN_FOLDER`, `ZIP_JAR`, Default `ZIP_JAR`) und liefert gültige sowie ungültige Plugins mit Fehlermeldungen zurück
* Jeder Plugin-Ort ist als `BUILTIN` oder extern klassifiziert und besitzt ein Sicherheitskonzept in Form einer geordneten, frei erweiterbaren Fallback-Kette von `PluginSecurityStrategy`-Strategien (kein Enum), mit ortsbezogenem Override der gesamten Kette; mitgelieferte Strategien: kein Check (ehemals `PLAIN`), Signatur (ehemals `MUST_SIGN`), Checksum (ehemals `CHECKSUM`)
* Die Signatur-Strategie bezieht den zu prüfenden Public Key über eine eigene, austauschbare `PublicKeyProviderStrategy`: Truststore (Java `KeyStore`), direkter `java.security.PublicKey`, Online-Plattform OpenPGP (RFC 9580, z. B. `keys.openpgp.org`)
* Plugins werden über isolierte `URLClassLoader` geladen, die den Zugriff auf den Code der Host-Anwendung per Reflection unterbinden, aber gezielt eine vom Host konfigurierte SDK-Whitelist freigeben
* Plugin-Abhängigkeiten (`required`/`optional`) bilden einen ClassLoader-Abhängigkeitsgraphen zur gezielten Klassensichtbarkeit zwischen Plugins
* ID-Kollisionen zwischen Orten werden über Versionsvergleich (Maven-Schema) und nachgelagerte Checksum-Prüfung aufgelöst
* Der Scan-/Ladevorgang blockiert intern nicht; Sicherheitsfreigaben (geänderte Checksum) erzeugen einen Pending-Status mit separatem Freigabe-/Reload-Mechanismus, den der Host asynchron ansteuern kann
* Jedes Plugin durchläuft definierte Lifecycle-Phasen (`onLoad`/`onEnable`/`onDisable`/`onUnload`), die von der Implementierung optional implementiert werden können
* Jedes Plugin besitzt einen persistenten Enabled/Disabled-Status (unabhängig vom Vorhandensein am Plugin-Ort), verwaltet über einen Callback des Framework-Nutzers
* Eine zur Laufzeit unbehandelte Exception innerhalb einer Extension-Implementierung führt zur dauerhaften Zwangsdeaktivierung genau dieses Plugins (bis zur manuellen Reaktivierung), nicht der gesamten Anwendung

## 4. Anforderungen

### Funktionale Anforderungen

* Manifest-Pflichtfelder: `$version`, `id`, `name`, `version`, `minVersion`, `icon`
* Manifest-Optionalfelder: `description`, `author` (`name` Pflicht, `mail` optional), `documentationUrl`, `sourceCodeUrl`, `copyright`, `license`
* `icon`: Base64-kodiert, Format per Magic-Bytes erkannt (SVG, PNG, JPG, ...)
* `license`: freier String, optional gegen SPDX-Identifier-Liste abgeglichen
* `version` und `minVersion`: Maven-Versionsschema, vergleichbar
* `extensions.<key>[]`: Liste von Objekten mit Pflichtfeld `implementation` (FQCN), konkrete Zusatzfelder je nach Extension-Point
* Konfigurationsklasse eines Extension-Points implementiert `ExtensionConfiguration<T>` (Pflichtfeld `implementation: KClass<out T>`) und trägt eine `@ExtensionPoint(key, exclusive)`-Annotation; der Host registriert seine Konfigurationsklassen beim Framework, die Plugin-Implementierungsklasse selbst trägt keine Annotation
* Decorator mappt das Extension-Objekt vollständig über die Registry, `implementation` liegt danach als instanziierte Factory/Singleton-Instanz vor
* Plugin-Abhängigkeiten im Manifest, je Abhängigkeit `required` oder `optional`
* Scanner erhält beim Start mehrere Plugin-Orte, je Ort: Lademodus, Builtin/Extern-Flag, optionales Sicherheits-Override (geordnete Strategie-Kette)
* Scanner-Ergebnis enthält gültige und ungültige Plugins (mit Fehlermeldungen) sowie Plugins im Status `PENDING_APPROVAL`
* Extension-Points ohne `exclusive`-Flag: mehrere Einträge gleichen Keys aus verschiedenen Plugins werden einfach zu einer Liste zusammengefügt, es ist kein Merge im engeren Sinne und keine Prioritäts-/Reihenfolgeregel erforderlich
* Extension-Points mit `exclusive`-Flag: Befüllen zwei Plugins denselben Key, werden **beide Plugins vollständig** nicht geladen, mit Log-Warnung inkl. beider Plugin-IDs und Key (aus Sicherheits-/Stabilitätsgründen wird nicht nur die einzelne Extension-Registrierung verworfen)
* ID-Kollision zwischen Orten: Log-Warnung, höhere Version gewinnt; bei Versionsgleichheit Checksum-Vergleich; bei Checksum-Gleichheit beliebige Auswahl, bei Checksum-Ungleichheit Sicherheitswarnung und keines der beiden laden
* `minVersion`-Prüfung gegen die Version der Host-Software, zu neue Plugins werden nicht geladen
* Plugin-Lifecycle-Hooks `onLoad`/`onEnable`/`onDisable`/`onUnload`, aufgerufen an den jeweiligen Übergängen
* Persistenter Enabled/Disabled-Status je Plugin-ID, verwaltet über einen vom Framework-Nutzer bereitgestellten Callback (Lesen/Schreiben); ein deaktiviertes Plugin bleibt gescannt, aber ohne aktive Extensions
* Laufzeit-Fehlerisolation: eine unbehandelte Exception aus einer Extension-Implementierung wird abgefangen, das betroffene Plugin wird dauerhaft zwangsdeaktiviert (`onDisable`/`onUnload` sofern möglich) und muss manuell reaktiviert werden; der Vorfall wird im Ergebnis/Log vermerkt

### Technische Anforderungen

* Kotlin, Gradle (siehe `development.md`)
* Manifest-Parsing über YAML mit synchronisiertem JSON-Schema und Data Classes; beide werden manuell parallel gepflegt (keine automatische Codegenerierung), Konsistenz wird über Tests abgesichert
* Plugin-Laden über `URLClassLoader`, ein Loader je Plugin-Einheit gemäß Lademodus
* ClassLoader-Isolation: Parent-Last-Strategie gegenüber der Host-Anwendung mit gezielt freigegebener SDK-Schicht; die SDK-Whitelist (freizugebende Pakete/Interfaces) wird dem Framework vom Host als Konfiguration übergeben, nicht vom Framework selbst vorgegeben
* ClassLoader-Abhängigkeitsgraph zwischen Plugin-ClassLoadern, Zyklenerkennung, Ladereihenfolge nach Abhängigkeiten
* Sicherheitskonzept als Strategy-Pattern: `PluginSecurityStrategy`-Interface, ausschließlich über neue Implementierungen erweiterbar, kein Enum; ein Plugin-Ort konfiguriert eine geordnete, nicht-leere Liste dieser Strategien als Fallback-Kette
* Die Kette wird strikt in Konfigurationsreihenfolge geprüft: die erste erfolgreich bestandene Strategie beendet die Prüfung positiv; ein Sicherheitsproblem wird erst gemeldet, wenn ALLE Strategien der Kette fehlgeschlagen sind
* Signatur-Strategie (ehemals `MUST_SIGN`) prüft je Lademodus:
  * `SINGLE_JAR`: klassische JAR-Signatur der einen JAR
  * `MULTI_JAR_WITH_OWN_FOLDER`: Signatur des Manifest-JARs, das zusätzlich eine Checksummenliste aller übrigen JARs im Ordner als signierte Nutzdaten enthält
  * `ZIP_JAR`: Signatur des gesamten ZIP
* Die Signatur-Strategie bezieht den zu prüfenden Public Key nicht mehr über ein einzelnes Callback, sondern über eine eigene, austauschbare `PublicKeyProviderStrategy` (Dependency Injection), mit drei mitgelieferten Implementierungen:
  * Truststore-Provider: Public Key aus einem Java `KeyStore` (Trust-Store-Datei/Alias)
  * Direkter Provider: unmittelbar vom Framework-Nutzer übergebener `java.security.PublicKey`
  * OpenPGP-Online-Provider: Auflösung über einen HKP-kompatiblen OpenPGP-Keyserver nach RFC 9580 (Referenz `keys.openpgp.org`), inkl. Parsing des OpenPGP-Schlüsselmaterials zu einem verifizierbaren Public Key
* Checksum-Strategie (ehemals `CHECKSUM`) über Soll-Checksum-Callback (kann `NULL` liefern); Erstfreigabe bzw. Checksum-Änderung führt zu Status `PENDING_APPROVAL`, kein blockierender Callback im Scan-Pfad
* ZIP-Entpacken erfolgt in ein temporäres Verzeichnis, das per `deleteOnExit` bereinigt wird
* Das Framework legt sich nicht auf eine konkrete Async-API (Coroutines, Futures, Callbacks) fest; alle Einstiegspunkte sind so gestaltet, dass der Host sie in dem von ihm gewählten Nebenläufigkeitsmodell blockierend oder nicht-blockierend aufrufen kann
* Lifecycle- und Enabled/Disabled-Callbacks dürfen den Ladevorgang ebenso wenig blockierend beeinträchtigen wie die Security-Callbacks
* Durchgängiges Logging des Scan-/Ladeprozesses (Orte, geladene/nicht geladene Plugins mit Begründung, enthaltene Extensions, Lifecycle-Übergänge) mit folgenden Log-Levels:
  * `INFO`: Scan-Start je Ort (Modus, Builtin/Extern, Sicherheitskonzept), erfolgreich geladenes Plugin, dauerhafte Enabled/Disabled-Änderung über Callback
  * `WARN`: ungültiges Manifest, fehlgeschlagene Sicherheitsprüfung, Status `PENDING_APPROVAL`, ID-Kollision zwischen Orten, exklusiver Extension-Konflikt
  * `DEBUG`: enthaltene Extensions eines Plugins (Key, Implementierungsklasse), Instanziierung einer `implementation`-Klasse durch den Decorator, Lifecycle-Übergänge (`onLoad`/`onEnable`/`onDisable`/`onUnload`)
  * `ERROR`: Zwangsdeaktivierung eines Plugins wegen unbehandelter Laufzeit-Exception in einer Extension

## 5. Architektur

* Komponenten:
  * **Manifest-Modul**: YAML-Parsing, JSON-Schema, Data Classes, Validierung (inkl. `$version`, Icon-Erkennung, SPDX-Abgleich)
  * **Extension-Modul**: `ExtensionConfiguration<T>`-Interface, `@ExtensionPoint(key, exclusive)`-Annotation an Host-Konfigurationsklassen, Host-Registry für Konfigurationsklassen, Decorator-Mapping, Listenaufbau pro Key
  * **Scanner-Modul**: Orte-Konfiguration (Lademodus, Builtin/Extern, Security-Override), Scan-Strategien je Lademodus, Ergebnis-Modell (gültig/ungültig/pending), temporäres Entpack-Verzeichnis mit `deleteOnExit`
  * **Security-Modul**: `PluginSecurityStrategy`-Interface, Fallback-Ketten-Auswertung, mitgelieferte Strategien (kein Check, Signatur, Checksum), Pending-/Freigabe-Mechanismus
  * **Public-Key-Provider-Modul**: `PublicKeyProviderStrategy`-Interface, mitgelieferte Implementierungen (Truststore, direkter Key, OpenPGP-Keyserver nach RFC 9580), Einbindung in die Signatur-Strategie
  * **ClassLoader-Modul**: Isolierte `URLClassLoader`-Erzeugung je Lademodus, vom Host konfigurierte SDK-Whitelist, Abhängigkeitsgraph zwischen Plugin-ClassLoadern
  * **Lifecycle-Modul**: Lifecycle-Hooks (`onLoad`/`onEnable`/`onDisable`/`onUnload`), persistenter Enabled/Disabled-Status über Callback, Laufzeit-Fehlerisolation mit dauerhafter Zwangsdeaktivierung
  * **Orchestrierung/Runtime-Modul**: Zusammenspiel Scanner → Security → ClassLoader → Manifest/Extension-Mapping → Lifecycle, host-gesteuerte Nebenläufigkeit, ID-Kollisionsauflösung, `minVersion`-Prüfung
* Datenfluss: Plugin-Orte → Scanner (pro Lademodus) → Security-Prüfung (Signatur/Checksum, ggf. Pending) → ClassLoader-Erzeugung (unter Beachtung Abhängigkeitsgraph und SDK-Whitelist) → Manifest-Deserialisierung + Validierung → Extension-Decorator-Mapping (inkl. Prüfung auf exklusive Konflikte) → Lifecycle-Aktivierung (`onLoad`/`onEnable`, abhängig vom persistenten Status) → Ergebnis (geladene Plugins, Extensions, Fehler, Pending-Liste)
* Externe Schnittstellen (durch Framework-Nutzer bereitzustellen): Public-Key-Callback, Soll-Checksum-Callback, Freigabe-/Persistenz-Callback für akzeptierte Checksums, Persistenz-Callback für den Enabled/Disabled-Status je Plugin, SDK-Whitelist-Konfiguration
* Persistenz: keine eigene vorgesehen; akzeptierte Checksums und Enabled/Disabled-Status werden über die bereitgestellten Callbacks verwaltet (Speicherort liegt beim Framework-Nutzer); entpackte ZIP-Plugins liegen im Temp-Verzeichnis und werden per `deleteOnExit` entfernt
* MkDocs-Struktur (Zielgruppentrennung Plugin-Entwickler/Host-Integratoren), je Seite der zuständige Implementierungsplan:
  * `index.md` — Übersicht/Kernkonzepte — IP-01 (Erstellung), IP-07 (Verweis auf Unterseiten)
  * `plugin-development/manifest.md` — Manifest-Felder + Beispiel — IP-01
  * `plugin-development/extension-points.md` — Extension-Point erstellen/konsumieren + Beispiel — IP-02
  * `plugin-development/dependencies.md` — required/optional Abhängigkeiten, Helper-Klassen-Pattern — IP-05
  * `plugin-development/lifecycle.md` — Lifecycle-Hooks aus Plugin-Sicht — IP-06
  * `host-integration/setup.md` — Plugin-Orte, Lademodi, Start-/Reload-API — IP-03 (Erstellung), IP-07 (Ergänzung API)
  * `host-integration/security.md` — Sicherheitskonzepte, Strategy-Kette + Beispiel — IP-04
  * `host-integration/public-key-providers.md` — Public-Key-Provider-Strategien + Beispiel — IP-08
  * `host-integration/sdk-whitelist.md` — SDK-Whitelist-Konfiguration des Hosts — IP-05
  * `host-integration/plugin-lifecycle-management.md` — Enabled/Disabled-Callback, Deaktivierungsgründe — IP-06
  * `troubleshooting.md` — Log-Level-Übersicht, Fehlerfälle (ID-Kollision, exklusiver Konflikt, minVersion) — IP-07

## 6. Übersicht Implementierungspläne

| ID    | Implementierungsplan                          | Ziel                                                                 | Abhängigkeiten |
|-------|------------------------------------------------|-----------------------------------------------------------------------|----------------|
| IP-01 | Manifest-Schema & Data Classes (COMPLETED)     | YAML/JSON-Schema/Data-Class-Synchronisation, Validierung, Icon/SPDX   | -              |
| IP-02 | Extension-Point-Mechanismus (COMPLETED)        | Host-Registry, `@ExtensionPoint`-Annotation, Decorator-Mapping, Listenaufbau, Exklusivitäts-Konflikt | IP-01          |
| IP-03 | Plugin-Scanner & Lademodi                      | Scan-Strategien SINGLE_JAR/MULTI_JAR_WITH_OWN_FOLDER/ZIP_JAR, Temp-Entpacken | IP-01          |
| IP-04 | Sicherheitskonzept (Strategy-Kette)             | `PluginSecurityStrategy`-Interface, Fallback-Kette, mitgelieferte Basis-Strategien, Pending-/Freigabe-Mechanismus | IP-03          |
| IP-05 | ClassLoader-Isolation & Abhängigkeitsgraph      | Parent-Last-Isolation, host-konfigurierte SDK-Whitelist, Plugin-Abhängigkeitsgraph | IP-01, IP-03   |
| IP-06 | Lifecycle & Fehlerisolation                    | Lifecycle-Hooks, Enabled/Disabled-Status, dauerhafte Zwangsdeaktivierung | IP-02, IP-05   |
| IP-07 | Orchestrierung & Laufzeit-Runtime              | Host-steuerbare Gesamtsteuerung, ID-Kollisionsauflösung, minVersion-Check | IP-04, IP-06 |
| IP-08 | Public-Key-Provider-Strategien                  | `PublicKeyProviderStrategy`-Interface, Truststore-, Direkt- und OpenPGP-Provider | IP-04          |

## 7. Implementierungspläne

### IP-01: Manifest-Schema & Data Classes (COMPLETED)

**Ziel**

Definiert das Plugin-Manifest als Single Source of Truth mit synchronisiertem JSON-Schema und Kotlin-Data-Classes.

**Umfang**

Enthält: Manifest-Felder (`$version`, `id`, `name`, `version`, `minVersion`, `description`, `author`, `documentationUrl`, `sourceCodeUrl`, `icon`, `copyright`, `license`, `dependencies`, `extensions`-Grundgerüst mit `implementation`), YAML-Parsing für `.yml`/`.yaml`, Icon-Magic-Byte-Erkennung, SPDX-Abgleich für `license`, Maven-Versionsvergleich für `version`/`minVersion`.
Enthält nicht: konkrete Extension-Konfigurationsklassen (IP-02), Scanner-Logik (IP-03).

**Betroffene Bereiche**

Neues Manifest-Modul (Paketstruktur noch festzulegen), JSON-Schema-Datei(en), Data Classes, Versionsvergleichs-Utility.

**Abhängigkeiten**

Keine.

**Erwartetes Ergebnis**

Ein Manifest-YAML kann geladen, gegen das Schema validiert und verlustfrei in Data Classes gemappt werden; Icon-Format und Lizenz-Identifier werden erkannt.

**Technische Hinweise**

JSON-Schema und Data Classes werden manuell parallel gepflegt (keine Codegenerierung in eine Richtung); die Konsistenz zwischen beiden wird durch dedizierte Tests abgesichert, die bei jeder Feldänderung mitgepflegt werden müssen.

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

`documentationUrl`/`sourceCodeUrl` wurden zu `links.documentation`/`links.sourceCode` gruppiert, `copyright`/`license` zu `legal.copyright`/`legal.license`. `$version` ist rein intern (Migrationszwecke) und auf `PluginManifest` bewusst nicht exponiert. YAML-Parsing über Jackson (`jackson-dataformat-yaml`/`jackson-module-kotlin`), Schema-Validierung über `com.networknt:json-schema-validator`. Icon-Erkennung nutzt `ImageIO` für alle dort registrierten Rasterformate statt einzeln implementierter Magic-Byte-Prüfungen je Format, SVG wird separat per XML-Sniffing erkannt. Der Maven-Versionsvergleich wurde nicht selbst implementiert, sondern über die Dependency `org.apache.maven:maven-artifact` (`ComparableVersion`) bezogen. Sämtliche reinen Implementierungsdetails (`ManifestParser`, `ManifestValidationException`, `IconDetector`, `IconFormatException`, `SpdxLicenses`) sind `internal`; nur das Manifest-Datenmodell (`PluginManifest`, `Author`, `Links`, `Legal`, `PluginDependency`, `ExtensionEntry`) ist `public`.

### IP-02: Extension-Point-Mechanismus (COMPLETED)

**Ziel**

Ermöglicht die generische Deklaration von Extension-Points in `extensions.<key>[]` und deren typsichere Auflösung über Annotation und Decorator.

**Umfang**

Enthält: `ExtensionConfiguration<T>`-Interface (Pflichtfeld `implementation: KClass<out T>`), `@ExtensionPoint(key, exclusive)`-Annotation an Host-Konfigurationsklassen (nicht an Plugin-Implementierungsklassen), Host-Registry für Konfigurationsklassen, Decorator-Mapping (Rohdaten → typisiertes Objekt inkl. instanziierter `implementation`), Listenaufbau für mehrere Plugins mit gleichem, nicht-exklusivem Key, Konflikterkennung und -behandlung bei exklusiven Keys, Definition einer `ExtensionClassResolver`-Schnittstelle (löst einen FQCN zu einer `Class`-Instanz auf) samt einfacher Standardimplementierung auf Basis des aufrufenden ClassLoaders.
Enthält nicht: die spätere isolierte Implementierung des `ExtensionClassResolver` auf Basis eigener Plugin-ClassLoader (IP-05 ersetzt nur die Implementierung hinter der in IP-02 definierten Schnittstelle, nicht die Schnittstelle selbst).

**Betroffene Bereiche**

Extension-Modul, Annotation-Definition, Decorator-Implementierung.

**Abhängigkeiten**

IP-01 (Manifest-Daten als Eingabe).

**Erwartetes Ergebnis**

Ein `extensions`-Eintrag wird anhand der über die Registry ermittelten, `@ExtensionPoint`-annotierten Konfigurationsklasse gemappt, die Plugin-Implementierungsklasse liegt als instanziiertes Singleton vor. Bei nicht-exklusiven Keys werden alle Einträge mehrerer Plugins zu einer Liste zusammengeführt. Befüllen zwei Plugins denselben `exclusive`-Key, werden beide Plugins vollständig nicht geladen.

**Technische Hinweise**

Bei nicht-exklusiven Extension-Points ist kein Merge im engeren Sinne und keine Prioritäts-/Reihenfolgeregel nötig — alle Einträge stehen gleichberechtigt nebeneinander in der Liste.
Für Extension-Points, bei denen nur ein aktiver Eintrag sinnvoll ist (exklusiver Slot statt Liste), ist das Flag an der `@ExtensionPoint`-Annotation der Host-Konfigurationsklasse (`exclusive = true`) vorzusehen. Befüllen zwei voneinander unabhängige Plugins zur Laufzeit denselben exklusiven Key, ist das kein Fehler im Extension-Point-Schema des Host-Entwicklers, sondern ein unauflösbarer Konflikt der konkret installierten Plugin-Kombination: Aus Sicherheits- und Stabilitätsgründen werden dabei **beide gesamten Plugins** (nicht nur die einzelne Extension-Registrierung) nicht geladen, es wird eine Log-Warnung mit beiden Plugin-IDs und dem betroffenen Key ausgegeben (analog zur Behandlung der ID-Kollision zwischen Orten).
Die Plugin-Implementierungsklasse selbst trägt keinerlei Framework-Annotation oder -Marker-Interface; sie implementiert ausschließlich das vom Host definierte Plugin-API-Interface `T`.

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

Kein Marker-Interface für Plugin-Implementierungsklassen (im Dialog verworfen); die spätere Idee eines Lifecycle-Basis-Objekts wurde stattdessen als eigenständiges, optionales `PluginLifecycle`-Interface nach IP-06 verschoben. Die `@ExtensionPoint`-Annotation sitzt bewusst an der Host-Konfigurationsklasse (`key`, `exclusive`), nicht an der Plugin-Implementierung, damit Plugin-Entwickler ausschließlich das Host-Plugin-API-Interface kennen müssen. `ExtensionEntry` (IP-01) wurde um ein per `@JsonAnySetter` erfasstes `additionalProperties: Map<String, Any?>` erweitert, damit der Decorator Zugriff auf die extension-point-spezifischen Zusatzfelder hat, ohne das JSON-Schema zu ändern (dessen `extensionEntry`-Definition zusätzliche Felder bereits zuließ). Neue Klassen: `ExtensionConfiguration<T>`, `@ExtensionPoint`, `ExtensionPointRegistry` (Registrierung + Validierung), `ExtensionClassResolver`/`DefaultExtensionClassResolver`, `ExtensionDecorator` (internal), `ExtensionAggregator` mit Ergebnis `ExtensionAggregationResult` (`pluginResults`: ID/`Path`/`PluginExtensionStatus`, `extensionsByKey`). Konfigurationsklassen-Instanziierung erfolgt über Kotlin-Reflection (`primaryConstructor.callBy`) statt vollständigem Jackson-Tree-Mapping, da `implementation: KClass<out T>` kein direkt deserialisierbarer JSON-Typ ist. Logging über neu eingeführtes SLF4J (`slf4j-api`, Testlaufzeit `slf4j-simple`); dafür wurde `licensee` um `allowUrl("https://opensource.org/license/mit")` ergänzt, da slf4j-api seine Lizenz über eine URL statt einer SPDX-ID deklariert.

### IP-03: Plugin-Scanner & Lademodi

**Ziel**

Scannt konfigurierte Plugin-Orte nach den drei Lademodi und liefert ein strukturiertes Ergebnis mit gültigen und ungültigen Plugins.

**Umfang**

Enthält: Orte-Konfiguration (Pfad, Lademodus, Builtin/Extern-Flag), Scan-Strategien `SINGLE_JAR`, `MULTI_JAR_WITH_OWN_FOLDER`, `ZIP_JAR` (Default), ZIP-Entpacken in ein temporäres Verzeichnis mit `deleteOnExit`, Fehlerermittlung und -meldungen für ungültige Plugins.
Enthält nicht: Sicherheitsprüfung (IP-04), ClassLoader-Erzeugung (IP-05).

**Betroffene Bereiche**

Scanner-Modul, Dateisystem-Zugriff, Ergebnis-Modell.

**Abhängigkeiten**

IP-01 (Manifest muss lesbar/prüfbar sein, um Plugin als gültig/ungültig einzustufen).

**Erwartetes Ergebnis**

Für eine Liste konfigurierter Orte liefert der Scanner eine vollständige Liste erkannter Plugin-Kandidaten inkl. Rohdaten, getrennt nach gültig/ungültig mit Fehlermeldung. ZIP-Plugins werden in ein Temp-Verzeichnis entpackt, das beim Beenden der JVM automatisch bereinigt wird.

**Technische Hinweise**

Keine weitere manuelle Cache-Verwaltung erforderlich, da `deleteOnExit` die Bereinigung übernimmt; bei sehr langlaufenden Prozessen mit vielen Reloads ist zu beachten, dass `deleteOnExit`-Einträge erst beim JVM-Ende tatsächlich entfernt werden.

### IP-04: Sicherheitskonzept (Strategy-Kette)

**Ziel**

Setzt das Sicherheitskonzept als Strategy-Pattern mit konfigurierbarer Fallback-Kette je Plugin-Ort um, inkl. Freigabe-Mechanismus für Checksum-Änderungen.

**Umfang**

Enthält: `PluginSecurityStrategy`-Interface (Prüf-Operation je Plugin-Kandidat, Ergebnis mit Erfolg/Fehlschlag/`PENDING_APPROVAL`), Ketten-Auswertungslogik (Reihenfolge, erster Erfolg beendet Prüfung positiv, Gesamtfehlschlag nur wenn alle Strategien scheitern), mitgelieferte Strategien: "kein Check" (ehemals `PLAIN`), Signatur (ehemals `MUST_SIGN`, Prüfung je Lademodus inkl. Checksummenliste für `MULTI_JAR_WITH_OWN_FOLDER`, bezieht den Public Key über eine injizierte `PublicKeyProviderStrategy`, siehe IP-08), Checksum (ehemals `CHECKSUM`, Soll-Checksum-Callback, Status `PENDING_APPROVAL`, Freigabe-/Persistenz-Callback für akzeptierte Checksums), Default-Ketten nach Builtin/Extern, ortsbezogenes Override der gesamten Kette.
Enthält nicht: konkrete `PublicKeyProviderStrategy`-Implementierungen (IP-08, die Signatur-Strategie erhält hier nur die Schnittstelle als Injektionspunkt), ClassLoader-Erzeugung selbst (IP-05), UI für Freigabe-Dialoge (liegt beim Framework-Nutzer).

**Betroffene Bereiche**

Security-Modul, Scanner-Ergebnis-Erweiterung um Pending-Status.

**Abhängigkeiten**

IP-03 (Scan-Ergebnis als Eingabe für Sicherheitsprüfung).

**Erwartetes Ergebnis**

Ein Plugin-Ort besitzt eine geordnete, beliebig erweiterbare Liste von Sicherheitsstrategien. Jedes gescannte Plugin wird strategieweise geprüft; ein Sicherheitsproblem wird erst gemeldet, wenn alle konfigurierten Strategien fehlgeschlagen sind. Eine spätere Freigabe (Checksum-Strategie) löst gezielt einen Reload aus, ohne dass das Framework selbst den Scan blockiert. Framework-Nutzer können eigene Strategien ergänzen, ohne den Ketten-Mechanismus selbst anzufassen.

**Technische Hinweise**

Callback-Schnittstellen (Checksum, Freigabe) sind als einfache, synchron aufrufbare Funktions-Interfaces zu gestalten; ob der Host sie synchron oder aus einer eigenen Coroutine/einem eigenen Executor heraus aufruft, liegt vollständig beim Host (siehe Architekturentscheidung zur Async-API in Abschnitt 4).
Die Verrechnung von `PENDING_APPROVAL` einer einzelnen Strategie innerhalb der Kette (sofortiger Kettenabbruch vs. Weiterprüfung nachfolgender Strategien) ist vor Beginn der Detailplanung zu klären (siehe Abschnitt 9).
Die Signatur-Strategie erhält ihre `PublicKeyProviderStrategy` als Konstruktor-/Konfigurationsparameter (Dependency Injection), damit IP-08 die konkrete Public-Key-Beschaffung austauschen kann, ohne IP-04 zu ändern.

### IP-05: ClassLoader-Isolation & Abhängigkeitsgraph

**Ziel**

Erzeugt isolierte `URLClassLoader` je Plugin-Einheit und verwaltet Sichtbarkeit zwischen Plugins gemäß deklarierten Abhängigkeiten.

**Umfang**

Enthält: Parent-Last-Strategie gegenüber Host-ClassLoader mit gezielt freigegebener, vom Host konfigurierten SDK-Whitelist, ClassLoader-Erzeugung je Lademodus (ein/mehrere JARs, entpacktes ZIP), Abhängigkeitsgraph zwischen Plugin-ClassLoadern (`required`/`optional`), Zyklenerkennung, Ladereihenfolge, eine isolierte Implementierung der in IP-02 definierten `ExtensionClassResolver`-Schnittstelle auf Basis der jeweiligen Plugin-ClassLoader (löst die Standardimplementierung aus IP-02 in der Orchestrierung, IP-07, ab).
Enthält nicht: Instanziierung der eigentlichen Extension-Implementierung selbst — das bleibt Aufgabe des Decorators aus IP-02, der lediglich die hier bereitgestellte `ExtensionClassResolver`-Implementierung nutzt; Sicherheitsprüfung vor dem Laden (IP-04).

**Betroffene Bereiche**

ClassLoader-Modul, SDK-Whitelist-Konfigurationsschnittstelle, Abhängigkeitsgraph-Datenstruktur.

**Abhängigkeiten**

IP-01 (Abhängigkeitsdeklaration im Manifest), IP-03 (welche Dateien/Ordner pro Plugin geladen werden).

**Erwartetes Ergebnis**

Plugins können weder per Reflection noch über den Klassenpfad auf Host-internen Code zugreifen, außer über die vom Host explizit konfigurierte SDK-Whitelist; deklarierte Plugin-Abhängigkeiten sind zur Ladezeit aufgelöst, fehlende `required`-Abhängigkeiten führen zu ungültigem Plugin, fehlende `optional`-Abhängigkeiten zu eingeschränkter Funktionalität ohne Ladefehler.

**Technische Hinweise**

Die SDK-Whitelist (Pakete/Interfaces, die Plugins sichtbar sind) wird nicht vom Framework vorgegeben, sondern vom Host bei der Initialisierung übergeben, da nur der Host seine eigene SDK-Schicht kennt.
Für optionale Plugin-Abhängigkeiten reicht ein reines `if`-Guard im selben Methodenkörper nur bedingt: JVM-Klassenreferenzen werden zwar meist lazy aufgelöst (nicht betretener Zweig lädt die fremde Klasse nie), das gilt aber nicht, wenn die fremde Klasse als Elternklasse/Interface, Feldtyp oder in einer Methodensignatur der eigenen Klasse auftaucht. Empfohlenes Pattern: jede Nutzung von Klassen einer optionalen Abhängigkeit in eine eigene Helper-Klasse kapseln, die selbst erst innerhalb des `if`-Zweigs geladen wird, damit bei fehlendem Plugin niemals der Versuch entsteht, eine Klasse der fehlenden Abhängigkeit zu laden.

### IP-06: Lifecycle & Fehlerisolation

**Ziel**

Verwaltet den Lebenszyklus jedes Plugins (Laden, Aktivieren, Deaktivieren, Entladen) sowie den persistenten Enabled/Disabled-Status und die Isolation von Laufzeitfehlern einzelner Plugins.

**Umfang**

Enthält: optionales `PluginLifecycle`-Interface mit Hook-Methoden `onLoad`/`onEnable`/`onDisable`/`onUnload` (frei von beliebigen Klassen implementierbar, Aufrufreihenfolge über mehrere Implementierungen NICHT deterministisch), persistenter Enabled/Disabled-Status je Plugin-ID über einen Lese-/Schreib-Callback des Framework-Nutzers, Abfangen unbehandelter Exceptions aus Extension-Aufrufen zur Laufzeit mit anschließender dauerhafter Zwangsdeaktivierung genau des betroffenen Plugins.
Enthält nicht: Instanziierung der Extension-Implementierung selbst (IP-02), ClassLoader-Erzeugung/-Schließung (IP-05).

**Betroffene Bereiche**

Neues Lifecycle-Modul mit dem `PluginLifecycle`-Interface, Fehlerbehandlung rund um Extension-Aufrufe.

**Abhängigkeiten**

IP-02 (Extension-Instanzen als Ziel der Hooks), IP-05 (ClassLoader-Zugriff für ein etwaiges Entladen beim `onUnload`).

**Erwartetes Ergebnis**

Ein Plugin durchläuft beim Laden/Entladen nachvollziehbar seine Lifecycle-Phasen; ein per Callback dauerhaft deaktiviertes Plugin bleibt zwar bekannt/gescannt, liefert aber keine aktiven Extensions; eine zur Laufzeit unbehandelte Exception in einer Extension führt zur dauerhaften Zwangsdeaktivierung nur dieses einen Plugins (bis zur manuellen Reaktivierung durch den Nutzer), die übrige Anwendung bleibt unbeeinträchtigt.

**Technische Hinweise**

Die Zwangsdeaktivierung nach einem Laufzeitfehler gilt dauerhaft und übersteht auch einen Neustart, bis das Plugin manuell reaktiviert wird; sie nutzt denselben persistenten Enabled/Disabled-Callback-Mechanismus wie eine bewusste Nutzer-Deaktivierung, sollte aber im Callback als eigener Grund (z. B. "durch Laufzeitfehler deaktiviert" vs. "durch Nutzer deaktiviert") unterscheidbar sein, damit der Host dies in der UI unterschiedlich darstellen kann.

### IP-07: Orchestrierung & Laufzeit-Runtime

**Ziel**

Verbindet Scanner, Security, ClassLoader und Lifecycle zu einem vom Host steuerbaren Gesamtablauf inklusive ID-Kollisionsauflösung und `minVersion`-Prüfung.

**Umfang**

Enthält: Gesamtsteuerung des Ladevorgangs über mehrere Orte mit synchron aufrufbaren Einstiegspunkten, ID-Kollisionsauflösung (Log-Warnung, Versionsvergleich, Checksum-Vergleich, Sicherheitswarnung bei Ungleichheit), `minVersion`-Prüfung gegen Host-Version, finale Ergebnisstruktur (geladene Plugins, Extensions, Fehler, Pending-Liste, deaktivierte Plugins), gezielter Reload einzelner Plugins nach Freigabe oder nach Reaktivierung.
Enthält nicht: UI-Darstellung der Ergebnisse (liegt beim Framework-Nutzer), jegliche Nebenläufigkeits-/Async-Infrastruktur (liegt beim Host).

**Betroffene Bereiche**

Orchestrierungs-/Runtime-Modul, öffentliche API des Frameworks.

**Abhängigkeiten**

IP-04, IP-06.

**Erwartetes Ergebnis**

Der Framework-Nutzer kann das Gesamtsystem mit einer Liste von Orten starten (synchron aufrufbare API), das vollständige Ergebnis erhalten (inkl. Fehlern, Pending- und deaktivierten Plugins) und nach Nutzerfreigabe bzw. Reaktivierung gezielt einzelne Plugins nachladen; wie der Host diese Aufrufe zeitlich einbettet (Thread, Coroutine, Executor), bleibt allein seine Entscheidung.

**Technische Hinweise**

Keine framework-eigene Async-API — die öffentliche API besteht aus normalen (ggf. blockierenden) Funktionsaufrufen, die der Host bei Bedarf selbst in einen eigenen Thread/Coroutine/Executor auslagert.

### IP-08: Public-Key-Provider-Strategien

**Ziel**

Macht die Herkunft des für die Signatur-Strategie (IP-04) benötigten Public Keys über eine eigene Strategy-Schnittstelle austauschbar und liefert drei konkrete Implementierungen.

**Umfang**

Enthält: `PublicKeyProviderStrategy`-Interface (liefert einen `java.security.PublicKey` zu einer Plugin-/Signatur-Kennung), Truststore-Implementierung (Java `KeyStore`, Alias-Auflösung), Direkt-Implementierung (unmittelbar übergebener `PublicKey`), OpenPGP-Implementierung (Auflösung über einen HKP-kompatiblen Keyserver nach RFC 9580, Referenz `keys.openpgp.org`, Parsing des OpenPGP-Schlüsselmaterials zu einem verwendbaren `PublicKey`), Fehlerbehandlung bei nicht auflösbarem Key (kein Absturz, definiertes Fehlschlag-Ergebnis an die Signatur-Strategie aus IP-04).
Enthält nicht: die Signaturprüfung selbst (IP-04), Auswahl/Konfiguration der Abhängigkeit für OpenPGP-Parsing ohne Rücksprache mit dem Nutzer (siehe `dependencies.md`).

**Betroffene Bereiche**

Neues Public-Key-Provider-Modul, Anbindung an die Signatur-Strategie aus IP-04, neue Abhängigkeit für OpenPGP-Verarbeitung (abstimmungspflichtig).

**Abhängigkeiten**

IP-04 (Signatur-Strategie definiert die Schnittstelle, über die ein Provider eingebunden wird).

**Erwartetes Ergebnis**

Die Signatur-Strategie kann wahlweise mit einem Truststore-, einem Direkt- oder einem OpenPGP-Provider betrieben werden, austauschbar je Plugin-Ort oder global; ein nicht auflösbarer Key führt zu einem nachvollziehbaren Fehlschlag der Signatur-Strategie, nicht zu einem Absturz.

**Technische Hinweise**

Für den OpenPGP-Provider ist vor Beginn der Detailplanung zu klären, welche Bibliothek RFC 9580 abdeckt und ob sie als neue Abhängigkeit freigegeben wird (siehe `dependencies.md`); ebenso ist das Zeitverhalten (Timeout, Caching aufgelöster Keys) und die Fehlerbehandlung bei nicht erreichbarem Keyserver festzulegen, damit der nicht-blockierende Scan-/Ladepfad (Abschnitt 4) nicht verletzt wird.

## 8. Abhängigkeitsgraph

```text
IP-01 (COMPLETED)
├── IP-02 (COMPLETED)
│   └── IP-06
│       └── IP-07
├── IP-03
│   ├── IP-04
│   │   ├── IP-07
│   │   └── IP-08
│   └── IP-05
│       └── IP-06
```

## 9. Risiken und offene Fragen

* Genaue Konfigurationsschnittstelle für die vom Host bereitgestellte SDK-Whitelist (Format, Granularität: Paket- vs. Klassenebene) ist in der Detailplanung von IP-05 festzulegen
* Unterscheidbarkeit von Deaktivierungsgründen (Nutzer vs. Laufzeitfehler) im Enabled/Disabled-Callback ist in der Detailplanung von IP-06 zu konkretisieren
* Verrechnung von `PENDING_APPROVAL` einer einzelnen Strategie innerhalb der Fallback-Kette (sofortiger Kettenabbruch vs. Weiterprüfung nachfolgender Strategien) ist ungeklärt und muss vor der Detailplanung von IP-04 entschieden werden
* Bibliotheksauswahl für RFC-9580-konformes OpenPGP-Parsing ist offen und mit dem Nutzer gemäß `dependencies.md` abzustimmen
* Zeitverhalten (Timeout, Caching) und Fehlerbehandlung des OpenPGP-Keyserver-Zugriffs bei Netzwerkausfall sind in der Detailplanung von IP-08 zu klären

## 10. Kriterien für den Feature-Abschluss

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
* Ein über den Callback dauerhaft deaktiviertes Plugin liefert keine aktiven Extensions, bleibt aber im Scan-Ergebnis sichtbar
* Ein Plugin, dessen Extension zur Laufzeit eine unbehandelte Exception wirft, wird automatisch dauerhaft zwangsdeaktiviert und muss manuell reaktiviert werden, ohne die übrige Anwendung zu beeinträchtigen
* Befüllen zwei Plugins denselben exklusiven Extension-Point, werden beide vollständig nicht geladen und eine nachvollziehbare Log-Warnung ausgegeben
* Die öffentliche API des Frameworks lässt sich sowohl blockierend als auch aus einem vom Host gewählten nebenläufigen Kontext heraus verwenden
