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
* Der Host kann für ein einzelnes Plugin explizit einen Force-Load anfordern, der eine fehlgeschlagene Sicherheitsprüfung gezielt und nachvollziehbar protokolliert übergeht
* Plugins werden über isolierte `URLClassLoader` geladen, die den Zugriff auf den Code der Host-Anwendung per Reflection unterbinden, aber gezielt eine vom Host konfigurierte SDK-Whitelist freigeben
* Plugin-Abhängigkeiten (`required`/`optional`) bilden einen ClassLoader-Abhängigkeitsgraphen zur gezielten Klassensichtbarkeit zwischen Plugins
* ID-Kollisionen zwischen Orten werden über Versionsvergleich (Maven-Schema) und nachgelagerte Checksum-Prüfung aufgelöst
* Der Scan-/Ladevorgang blockiert intern nicht; Sicherheitsfreigaben (geänderte Checksum) erzeugen einen Pending-Status mit separatem Freigabe-/Reload-Mechanismus, den der Host asynchron ansteuern kann
* Jedes Plugin durchläuft definierte Lifecycle-Phasen (`onLoad`/`onEnable`/`onDisable`/`onUnload`), die von der Implementierung optional implementiert werden können; ein Deaktivieren schließt immer das Verwerfen des Plugin-ClassLoaders ein, eine Reaktivierung erfordert einen vollständigen Neu-Ladevorgang inkl. erneuter Sicherheitsprüfung
* Jedes Plugin besitzt einen persistenten Enabled/Disabled-Status (unabhängig vom Vorhandensein am Plugin-Ort), verwaltet über eine austauschbare `PluginPersistenceStrategy` (Strategy-Pattern, kein reines Callback-Paar mehr; mitgelieferte Implementierungen: kein Persistieren, Host-Callback, Datei, Datenbank via JDBC)
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
* Plugin-Lifecycle-Hooks `onLoad`/`onEnable`/`onDisable`/`onUnload`, aufgerufen an den jeweiligen Übergängen; `onLoad`/`onEnable` bzw. `onDisable`/`onUnload` sind aus Reihenfolgegründen bei Abhängigkeiten getrennt, nicht zur Abbildung von Enabled/Disabled
* Persistenter Enabled/Disabled-Status je Plugin-ID über eine austauschbare `PluginPersistenceStrategy` (generisches Key-Value-Modell, mitgeliefert: kein Persistieren mit Produktiv-Warnung, Host-Callback, Datei in mehreren Formaten, Datenbank via JDBC); Status-Prüfung erfolgt VOR jedem Klassenladen der Extension, ein deaktiviertes Plugin bleibt gescannt, aber keine seiner Extension-Klassen wird geladen/instanziiert
* Laufzeit-Fehlerisolation über eine konfigurierbare `ExceptionHandlingStrategy` (Map `Exception-Typ → IGNORE/UNLOAD/CRASH`, Klassenhierarchie-Lookup, verschachtelbar über `parent`); Standard-Matrix für die mitgelieferten `PluginExecutionException`/`PluginFatalException` sowie beliebige Checked/Unchecked Exceptions; `UNLOAD` deaktiviert das betroffene Plugin dauerhaft (`onDisable`/`onUnload`, ClassLoader schließen) und muss manuell reaktiviert werden, der Vorfall wird im Ergebnis/Log vermerkt
* Durchsetzung der Fehlerisolation über einen Proxy: jede an den Host ausgelieferte Extension-Instanz ist ein JDK-Proxy (Interface) oder eine ByteBuddy-Subklasse (offene Klasse) über das Host-Plugin-API; Rückgabewerte werden rekursiv nach demselben Prinzip gewrappt (Interfaces/offene Klassen sowie deren Arrays/Collections/Map-Values), native/Standard-Typen (`java.*`/`javax.*`/`kotlin.*`/`javafx.*`) ausgenommen
* Force-Load: der Framework-Nutzer kann pro Plugin explizit das Laden trotz fehlgeschlagener Sicherheitsprüfung erzwingen; der Vorfall wird mit Plugin-ID und Grund der ursprünglich fehlgeschlagenen Prüfung protokolliert
* Reaktivierung eines deaktivierten Plugins löst zwingend einen erneuten Sicherheitsprüfungslauf (`PluginSecurity`) vor dem eigentlichen Neu-Laden (`PluginLoader`) aus, da das Plugin im deaktivierten Zustand verändert worden sein könnte

### Technische Anforderungen

* Kotlin, Gradle (siehe `development.md`)
* Manifest-Parsing über YAML mit synchronisiertem JSON-Schema und Data Classes; beide werden manuell parallel gepflegt (keine automatische Codegenerierung), Konsistenz wird über Tests abgesichert
* Plugin-Laden über `URLClassLoader`, gekapselt in einer eigenen `PluginLoader`-Klasse, ein Loader je Plugin-Einheit gemäß Lademodus
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
* Lifecycle- und Persistence-Strategie-Zugriffe dürfen den Ladevorgang ebenso wenig blockierend beeinträchtigen wie die Security-Callbacks
* Ein Force-Load ruft die `PluginLoader`-Klasse unabhängig vom Ergebnis der `PluginSecurityStrategy`-Kette auf; er ersetzt die Sicherheitsprüfung nicht dauerhaft, sondern übersteuert sie gezielt für genau einen Ladevorgang eines konkreten Plugins
* Persistenz-Strategie als Strategy-Pattern: `PluginPersistenceStrategy`-Interface (generisches `read`/`write` je Plugin-ID und Key), mitgeliefert: `NoPersistenceStrategy` (Default, WARN-Log), `CustomPersistenceStrategy` (Host-Callback), `FilePersistenceStrategy` (Properties/JSON/YAML/XML), `DatabasePersistenceStrategy` (reines JDBC über `javax.sql.DataSource`, kein ORM); wird sowohl für die Checksum-Persistenz (ehemals `ChecksumPersistenceCallback`) als auch den Enabled/Disabled-Status genutzt
* Exception-Handling als Strategy-Pattern: `ExceptionHandlingStrategy`-Interface (`Map<KClass<out Throwable>, ExceptionHandlingAction>` mit Klassenhierarchie-Lookup, optionalem `parent` zur Verschachtelung); mitgelieferte `DefaultExceptionHandlingStrategy` sowie Exception-Typen `PluginExecutionException`/`PluginFatalException`; ausschließlich host-weit konfigurierbar, keine plugin-individuelle Strategie
* Durchsetzung über einen Proxy: Extension-Instanzen werden nicht direkt ausgeliefert, sondern über `java.lang.reflect.Proxy` (Interface) bzw. eine ByteBuddy-Subklasse (offene Klasse, neue Dependency `net.bytebuddy:byte-buddy` + `org.objenesis:objenesis`) gewrappt; Rückgabewerte proxyter Methoden werden rekursiv nach demselben Prinzip behandelt, sofern ihr deklarierter Typ proxy-eligibel ist (Interface oder offene Klasse), inkl. Arrays/`Collection`/`Map`-Values; native/Standard-Typen (u. a. `java.*`, `javax.*`, `kotlin.*`, `javafx.*`) sind ausgenommen
* Zentraler Einstiegspunkt `PluginManager` (Root-Paket) mit Kotlin-Builder-DSL (`pluginManager { ... }`), bündelt die host-weite Konfiguration (Orte, Security-Default-Ketten, Dependency-Strategie, Persistence-Strategie, Exception-Handling-Strategie, SDK-Whitelist, Host-Version) und liefert vorkonfigurierte `PluginScanner`/`PluginSecurity`/`PluginLoader`-Instanzen
* Durchgängiges Logging des Scan-/Ladeprozesses (Orte, geladene/nicht geladene Plugins mit Begründung, enthaltene Extensions, Lifecycle-Übergänge) mit folgenden Log-Levels:
  * `INFO`: Scan-Start je Ort (Modus, Builtin/Extern, Sicherheitskonzept), erfolgreich geladenes Plugin, dauerhafte Enabled/Disabled-Änderung über `PluginPersistenceStrategy`
  * `WARN`: ungültiges Manifest, fehlgeschlagene Sicherheitsprüfung, Status `PENDING_APPROVAL`, ID-Kollision zwischen Orten, exklusiver Extension-Konflikt, Force-Load eines an der Sicherheitsprüfung gescheiterten Plugins, Einsatz von `NoPersistenceStrategy`, nicht proxybarer eigener Rückgabewerttyp, `ExceptionHandlingAction.CRASH` vor Durchführung
  * `DEBUG`: enthaltene Extensions eines Plugins (Key, Implementierungsklasse), Instanziierung einer `implementation`-Klasse durch den Decorator, Lifecycle-Übergänge (`onLoad`/`onEnable`/`onDisable`/`onUnload`)
  * `ERROR`: Zwangsdeaktivierung eines Plugins durch `ExceptionHandlingAction.UNLOAD`

## 5. Architektur

* Komponenten:
  * **Manifest-Modul**: YAML-Parsing, JSON-Schema, Data Classes, Validierung (inkl. `$version`, Icon-Erkennung, SPDX-Abgleich)
  * **Extension-Modul**: `ExtensionConfiguration<T>`-Interface, `@ExtensionPoint(key, exclusive)`-Annotation an Host-Konfigurationsklassen, Host-Registry für Konfigurationsklassen (inkl. Proxy-Eligibilitätsprüfung von `T`), Decorator-Mapping mit Proxy-Auslieferung (IP-06), Listenaufbau pro Key
  * **Scanner-Modul**: Orte-Konfiguration (Lademodus, Builtin/Extern, Security-Override), Scan-Strategien je Lademodus, Ergebnis-Modell (gültig/ungültig/pending), temporäres Entpack-Verzeichnis mit `deleteOnExit`
  * **Security-Modul**: `PluginSecurityStrategy`-Interface, Fallback-Ketten-Auswertung, mitgelieferte Strategien (kein Check, Signatur, Checksum), Pending-/Freigabe-Mechanismus
  * **Public-Key-Provider-Modul**: `PublicKeyProviderStrategy`-Interface, mitgelieferte Implementierungen (Truststore, direkter Key, OpenPGP-Keyserver nach RFC 9580), Einbindung in die Signatur-Strategie
  * **ClassLoader-Modul**: `PluginLoader`-Klasse (kapselt die eigentliche `URLClassLoader`-Erzeugung je Lademodus, aufrufbar sowohl regulär nach erfolgreicher Sicherheitsprüfung als auch gezielt per Force-Load unabhängig vom Sicherheitsergebnis, sowie das Schließen/Verwerfen eines Plugin-ClassLoaders bei Deaktivierung), vom Host konfigurierte SDK-Whitelist, Abhängigkeitsgraph zwischen Plugin-ClassLoadern
  * **Persistence-Modul**: `PluginPersistenceStrategy`-Interface, mitgelieferte Implementierungen (kein Persistieren, Host-Callback, Datei, Datenbank via JDBC), genutzt von Checksum-Persistenz (IP-04) und Enabled/Disabled-Status (IP-06)
  * **Lifecycle-Modul**: Lifecycle-Hooks (`onLoad`/`onEnable`/`onDisable`/`onUnload`), persistenter Enabled/Disabled-Status über `PluginPersistenceStrategy`, Laufzeit-Fehlerisolation über `ExceptionHandlingStrategy` (IGNORE/UNLOAD/CRASH), Proxy-Durchsetzung (JDK-Proxy/ByteBuddy) inkl. rekursivem Wrapping von Rückgabewerten, Security-Re-Check vor Reaktivierung
  * **PluginManager-Modul**: zentraler, per Kotlin-Builder konfigurierbarer Einstiegspunkt im Root-Paket, bündelt host-weite Konfiguration und liefert vorkonfigurierte `PluginScanner`/`PluginSecurity`/`PluginLoader`-Instanzen
  * **Orchestrierung/Runtime-Modul**: Zusammenspiel Scanner → Security → ClassLoader → Manifest/Extension-Mapping → Lifecycle, host-gesteuerte Nebenläufigkeit, ID-Kollisionsauflösung, `minVersion`-Prüfung, Force-Load-Einstiegspunkt zur gezielten Übersteuerung einer fehlgeschlagenen Sicherheitsprüfung, baut auf `PluginManager` auf
* Datenfluss: Plugin-Orte → Scanner (pro Lademodus) → Security-Prüfung (Signatur/Checksum, ggf. Pending) → ClassLoader-Erzeugung über `PluginLoader` (unter Beachtung Abhängigkeitsgraph und SDK-Whitelist; bei explizitem Force-Load auch ohne bzw. trotz negativem Sicherheitsergebnis) → Manifest-Deserialisierung + Validierung → Enabled/Disabled-Status-Prüfung über `PluginPersistenceStrategy` (VOR dem Decorator-Mapping) → Extension-Decorator-Mapping mit Proxy-Auslieferung nur für aktivierte Plugins (inkl. Prüfung auf exklusive Konflikte) → Lifecycle-Aktivierung (`onLoad`/`onEnable`) → Ergebnis (geladene Plugins, Extensions, Fehler, Pending-Liste, deaktivierte Plugins)
* Externe Schnittstellen (durch Framework-Nutzer bereitzustellen): Public-Key-Callback, Soll-Checksum-Callback, `PluginPersistenceStrategy` (Checksum- und Enabled/Disabled-Persistenz), `ExceptionHandlingStrategy` (optional, sonst Default-Matrix), SDK-Whitelist-Konfiguration, Force-Load-Aufruf je Plugin
* Persistenz: über die austauschbare `PluginPersistenceStrategy` (Default `NoPersistenceStrategy`, keine eigene Persistenz ohne explizite Host-Konfiguration); entpackte ZIP-Plugins liegen im Temp-Verzeichnis und werden per `deleteOnExit` entfernt
* MkDocs-Struktur (Zielgruppentrennung Plugin-Entwickler/Host-Integratoren), je Seite der zuständige Implementierungsplan:
  * `index.md` — Übersicht/Kernkonzepte — IP-01 (Erstellung), IP-07 (Verweis auf Unterseiten)
  * `plugin-development/manifest.md` — Manifest-Felder + Beispiel — IP-01
  * `plugin-development/extension-points.md` — Extension-Point erstellen/konsumieren + Beispiel — IP-02
  * `plugin-development/dependencies.md` — required/optional Abhängigkeiten, Helper-Klassen-Pattern — IP-05
  * `plugin-development/lifecycle.md` — Lifecycle-Hooks aus Plugin-Sicht — IP-06
  * `plugin-development/error-handling.md` — empfohlene Exceptions, Standard-Matrix, Proxy-Design-Regel, Debugging-Hinweise — IP-06
  * `host-integration/setup.md` — Plugin-Orte, Lademodi, Start-/Reload-API — IP-03 (Erstellung), IP-07 (Ergänzung API)
  * `host-integration/security.md` — Sicherheitskonzepte, Strategy-Kette + Beispiel — IP-04
  * `host-integration/public-key-providers.md` — Public-Key-Provider-Strategien + Beispiel — IP-08
  * `host-integration/sdk-whitelist.md` — SDK-Whitelist-Konfiguration des Hosts — IP-05
  * `host-integration/plugin-lifecycle-management.md` — Enabled/Disabled-Status, Deaktivierungsgründe, Reaktivierungsablauf — IP-06
  * `host-integration/persistence.md` — `PluginPersistenceStrategy`, Implementierungen, Produktiv-Empfehlung — IP-06
  * `host-integration/plugin-manager.md` — `PluginManager`, Builder-DSL, vorkonfigurierte Instanzen — IP-06 (Erstellung), IP-07 (Ergänzung Gesamtablauf)
  * `troubleshooting.md` — Log-Level-Übersicht, Fehlerfälle (ID-Kollision, exklusiver Konflikt, minVersion) — IP-07

## 6. Übersicht Implementierungspläne

| ID    | Implementierungsplan                          | Ziel                                                                 | Abhängigkeiten |
|-------|------------------------------------------------|-----------------------------------------------------------------------|----------------|
| IP-01 | Manifest-Schema & Data Classes (COMPLETED)     | YAML/JSON-Schema/Data-Class-Synchronisation, Validierung, Icon/SPDX   | -              |
| IP-02 | Extension-Point-Mechanismus (COMPLETED)        | Host-Registry, `@ExtensionPoint`-Annotation, Decorator-Mapping, Listenaufbau, Exklusivitäts-Konflikt | IP-01          |
| IP-03 | Plugin-Scanner & Lademodi (COMPLETED)          | Scan-Strategien SINGLE_JAR/MULTI_JAR_WITH_OWN_FOLDER/ZIP_JAR, Temp-Entpacken | IP-01          |
| IP-04 | Sicherheitskonzept (Strategy-Kette) (COMPLETED, Persistenz-Migration in IP-06) | `PluginSecurityStrategy`-Interface, Fallback-Kette, mitgelieferte Basis-Strategien, Checksum-Persistenz (ab IP-06 über `PluginPersistenceStrategy`) | IP-03          |
| IP-05 | ClassLoader-Isolation & Abhängigkeitsgraph (COMPLETED, Erweiterung in IP-06) | Parent-Last-Isolation, host-konfigurierte SDK-Whitelist, Plugin-Abhängigkeitsgraph, `PluginLoader`-Klasse inkl. Force-Load (Schließen des ClassLoaders folgt in IP-06) | IP-01, IP-03   |
| IP-06 | Lifecycle & Fehlerisolation (COMPLETED)        | `PluginManager`, Lifecycle-Hooks, `PluginPersistenceStrategy`, `PluginSecurity` (Rename+Re-Check), `ExceptionHandlingStrategy` mit Proxy-Durchsetzung (ByteBuddy) | IP-02, IP-05   |
| IP-07 | Orchestrierung & Laufzeit-Runtime (COMPLETED)   | Host-steuerbare Gesamtsteuerung auf Basis von `PluginManager`, ID-Kollisionsauflösung, minVersion-Check, Force-Load-Einstiegspunkt | IP-04, IP-06 |
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

**Nachträgliche Erweiterung durch IP-06 (Proxy-Durchsetzung)**

`ExtensionPointRegistry` prüft `T` seit IP-06 zusätzlich auf Proxy-Eligibilität (Interface oder offene, nicht-finale Klasse), da der Decorator dem Host seitdem nicht mehr die reale `implementation`-Instanz ausliefert, sondern eine Proxy-Instanz darüber (JDK-Proxy bzw. ByteBuddy-Subklasse) zur Durchsetzung der Laufzeit-Fehlerisolation. Details siehe IP-06.

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

Kein Marker-Interface für Plugin-Implementierungsklassen (im Dialog verworfen); die spätere Idee eines Lifecycle-Basis-Objekts wurde stattdessen als eigenständiges, optionales `PluginLifecycle`-Interface nach IP-06 verschoben. Die `@ExtensionPoint`-Annotation sitzt bewusst an der Host-Konfigurationsklasse (`key`, `exclusive`), nicht an der Plugin-Implementierung, damit Plugin-Entwickler ausschließlich das Host-Plugin-API-Interface kennen müssen. `ExtensionEntry` (IP-01) wurde um ein per `@JsonAnySetter` erfasstes `additionalProperties: Map<String, Any?>` erweitert, damit der Decorator Zugriff auf die extension-point-spezifischen Zusatzfelder hat, ohne das JSON-Schema zu ändern (dessen `extensionEntry`-Definition zusätzliche Felder bereits zuließ). Neue Klassen: `ExtensionConfiguration<T>`, `@ExtensionPoint`, `ExtensionPointRegistry` (Registrierung + Validierung), `ExtensionClassResolver`/`DefaultExtensionClassResolver`, `ExtensionDecorator` (internal), `ExtensionAggregator` mit Ergebnis `ExtensionAggregationResult` (`pluginResults`: ID/`Path`/`PluginExtensionStatus`, `extensionsByKey`). Konfigurationsklassen-Instanziierung erfolgt über Kotlin-Reflection (`primaryConstructor.callBy`) statt vollständigem Jackson-Tree-Mapping, da `implementation: KClass<out T>` kein direkt deserialisierbarer JSON-Typ ist. Logging über neu eingeführtes SLF4J (`slf4j-api`, Testlaufzeit `slf4j-simple`); dafür wurde `licensee` um `allowUrl("https://opensource.org/license/mit")` ergänzt, da slf4j-api seine Lizenz über eine URL statt einer SPDX-ID deklariert.

### IP-03: Plugin-Scanner & Lademodi (COMPLETED)

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

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

Kein `PluginLoadMode`-Enum: `PluginLocation.scanStrategy: PluginScanStrategy` referenziert die konkrete Strategie-Implementierung (`SingleJarScanStrategy`/`MultiJarWithOwnFolderScanStrategy`/`ZipJarScanStrategy`, Default `ZipJarScanStrategy`) direkt, da Lademodus und Strategie 1:1 entsprechen (im Dialog mit dem Nutzer entschieden). `PluginLocation.type: PluginLocationType` (`BUILTIN`/`EXTERNAL`) ersetzt das ursprünglich geplante Bool-Flag. Statt getrennter Kandidaten-/Ergebnisklassen (gültig/ungültig) gibt es ein einziges Ergebnismodell `PluginScanResult` mit `status: PluginScanStatus` (`LOADED`, `MANIFEST_NOT_FOUND`, `MANIFEST_INVALID`) und optionalem `errorMessage` (ebenfalls im Dialog entschieden). `PluginLocation.securityOverride: List<PluginSecurityStrategy> = emptyList()` ist bewusst nicht-nullable; dafür wurde im neuen Package `security` bereits ein leeres Marker-Interface `PluginSecurityStrategy` angelegt, das erst in IP-04 mit Inhalt gefüllt wird. Gemeinsame Manifest-Lookup-Logik (Ermittlung der Manifest-JAR unter mehreren JARs, Manifest-Parsing) liegt in einem internen `PluginManifestLookup`-Objekt, das von allen drei Strategien genutzt wird. Test-Fixtures (JAR/ZIP) werden zur Testlaufzeit programmatisch erzeugt statt als Binärdateien unter `src/test/resources` eingecheckt, um Diff-Lesbarkeit zu erhalten. Die `deleteOnExit`-Registrierung der ZIP-Strategie wird per Reflection auf `java.io.DeleteOnExitHook` verifiziert; dafür wurde `--add-opens java.base/java.io=ALL-UNNAMED` im Gradle-`test`-Task ergänzt.

### IP-04: Sicherheitskonzept (Strategy-Kette) (COMPLETED)

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
Ein Force-Load (siehe IP-05, IP-07) ist bewusst kein Bestandteil der Ketten-Auswertung selbst: die Kette liefert unverändert ein Fehlschlag-Ergebnis, das Übersteuern dieses Ergebnisses erfolgt ausschließlich außerhalb von IP-04, im Orchestrierungs-/ClassLoader-Bereich.

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

Im Dialog mit dem Nutzer wurde `PENDING_APPROVAL` als Framework-Zustand verworfen: Es gibt nur Erfolg/Fehlschlag je Strategie und `PluginScanStatus.SECURITY_PROBLEM` als Gesamtergebnis der Kette; eine etwaige Freigabe ist ausschließlich Host-Entscheidung (eigener Prompt-Dialog + manueller Force-Load über `PluginLoader`, IP-05/IP-07), nicht Teil von IP-04. Der Ur-Default je `PluginLocationType` (wenn `PluginScanner` keinen eigenen Eintrag in `defaultSecurityChains` hat) ist bewusst eine LEERE Liste statt eines impliziten "kein Check"-Defaults; ist die effektive Kette (Override ODER Typ-Default) leer, wirft `PluginScanner.scan` eine `IllegalStateException` ("keine Sicherheitsmechanismen angegeben"). "Kein Check" ist dafür als eigene, bewusst so benannte Strategie `InsecureSecurityStrategy` (statt `PLAIN`) modelliert, die explizit in eine Kette aufgenommen werden muss. Die Key-Identifikation für `PublicKeyProviderStrategy.resolve(pluginId: String)` erfolgt über die Plugin-ID aus dem Manifest, keine Manifest-Erweiterung nötig. Die Signaturprüfung nutzt für alle drei Lademodi durchgängig den Standard-JDK-Code-Signing-Mechanismus (`JarFile(..., verify = true)`, `CodeSigners`); für `ZIP_JAR` wird dieser unverändert direkt auf die reale `.zip`-Datei angewendet (ein signiertes JAR ist strukturell nur ein speziell aufgebautes ZIP), statt des nativen, im Dialog als nicht praktikabel verworfenen ZIP-Spec-Digitalsignatur-Felds (hätte eigenes Byte-Parsing plus eine neue PKCS#7/CMS-Abhängigkeit wie Bouncy Castle erfordert). Die Checksummenliste der Manifest-JAR-Signatur liegt unter der neuen, internen Konvention `META-INF/plugin-checksums.txt`. Checksum-Berechnung (für `ChecksumSecurityStrategy` und diese Checksummenliste) erfolgt im Nachgang (auf Nutzerwunsch) über ein austauschbares `ChecksumAlgorithm`-Interface (`id`, `digest(bytes): String`) statt fest verdrahtetem SHA-256; mitgeliefert wird die finale Klasse `MessageDigestChecksumAlgorithm(id: String)`, die einen beliebigen JCA-`MessageDigest`-Algorithmennamen kapselt (`"MD5"`, `"SHA-256"`, `"SHA-512"`, ...), Standard ist `MessageDigestChecksumAlgorithm("SHA-512")`; im Dialog bewusst keine separaten Unterklassen je Algorithmus. Checksum-Freigabe-Persistenz ist als eigenständiges Funktions-Interface `ChecksumPersistenceCallback` modelliert, das außerhalb der Kette von einem Force-Load aufgerufen wird (in IP-06 durch die generalisierte `PluginPersistenceStrategy` abgelöst, `ChecksumPersistenceCallback` entfernt). Als nachträgliche, im Dialog abgestimmte Anpassung von IP-03 wurde `ZipJarScanStrategy` von Entpacken in ein Temp-Verzeichnis auf Zugriff über eine gemountete NIO-Zip-Filesystem-Provider (`FileSystems.newFileSystem`) umgestellt, ohne den Zip-Inhalt jemals auf die Platte zu schreiben; `PluginScanResult.path` ist für ZIP-Kandidaten seitdem die reale `.zip`-Datei statt eines Temp-Verzeichnisses, der `deleteOnExit`-Mechanismus entfällt dafür vollständig. Die vormals separaten, nahezu identischen `PluginManifestLookup`-Funktionen für Ordner- und ZIP-Scan wurden auf eine gemeinsame private Implementierung zusammengeführt.

### IP-05: ClassLoader-Isolation & Abhängigkeitsgraph (COMPLETED)

**Ziel**

Erzeugt isolierte `URLClassLoader` je Plugin-Einheit über eine eigene `PluginLoader`-Klasse und verwaltet Sichtbarkeit zwischen Plugins gemäß deklarierten Abhängigkeiten.

**Umfang**

Enthält: eine `PluginLoader`-Klasse, die die eigentliche `URLClassLoader`-Erzeugung je Lademodus kapselt (ein/mehrere JARs, entpacktes ZIP) und sowohl regulär (nach erfolgreicher Sicherheitsprüfung) als auch gezielt per Force-Load-Parameter unabhängig vom Ergebnis der Sicherheitsprüfung (IP-04) aufgerufen werden kann; Parent-Last-Strategie gegenüber Host-ClassLoader mit gezielt freigegebener, vom Host konfigurierten SDK-Whitelist; Abhängigkeitsgraph zwischen Plugin-ClassLoadern (`required`/`optional`), Zyklenerkennung, Ladereihenfolge; eine isolierte Implementierung der in IP-02 definierten `ExtensionClassResolver`-Schnittstelle auf Basis der jeweiligen Plugin-ClassLoader (löst die Standardimplementierung aus IP-02 in der Orchestrierung, IP-07, ab).
Enthält nicht: Instanziierung der eigentlichen Extension-Implementierung selbst — das bleibt Aufgabe des Decorators aus IP-02, der lediglich die hier bereitgestellte `ExtensionClassResolver`-Implementierung nutzt; Aufruf-/Freigabelogik, WANN ein Force-Load ausgelöst werden darf (liegt bei der Orchestrierung, IP-07, bzw. beim Framework-Nutzer).

**Betroffene Bereiche**

ClassLoader-Modul (`PluginLoader`), SDK-Whitelist-Konfigurationsschnittstelle, Abhängigkeitsgraph-Datenstruktur.

**Abhängigkeiten**

IP-01 (Abhängigkeitsdeklaration im Manifest), IP-03 (welche Dateien/Ordner pro Plugin geladen werden).

**Erwartetes Ergebnis**

Plugins können weder per Reflection noch über den Klassenpfad auf Host-internen Code zugreifen, außer über die vom Host explizit konfigurierte SDK-Whitelist; deklarierte Plugin-Abhängigkeiten sind zur Ladezeit aufgelöst, fehlende `required`-Abhängigkeiten führen zu ungültigem Plugin, fehlende `optional`-Abhängigkeiten zu eingeschränkter Funktionalität ohne Ladefehler. Der Host kann über die `PluginLoader`-Klasse ein einzelnes, an der Sicherheitsprüfung gescheitertes Plugin gezielt per Force-Load dennoch laden lassen.

**Technische Hinweise**

Die SDK-Whitelist (Pakete/Interfaces, die Plugins sichtbar sind) wird nicht vom Framework vorgegeben, sondern vom Host bei der Initialisierung übergeben, da nur der Host seine eigene SDK-Schicht kennt.
Für optionale Plugin-Abhängigkeiten reicht ein reines `if`-Guard im selben Methodenkörper nur bedingt: JVM-Klassenreferenzen werden zwar meist lazy aufgelöst (nicht betretener Zweig lädt die fremde Klasse nie), das gilt aber nicht, wenn die fremde Klasse als Elternklasse/Interface, Feldtyp oder in einer Methodensignatur der eigenen Klasse auftaucht. Empfohlenes Pattern: jede Nutzung von Klassen einer optionalen Abhängigkeit in eine eigene Helper-Klasse kapseln, die selbst erst innerhalb des `if`-Zweigs geladen wird, damit bei fehlendem Plugin niemals der Versuch entsteht, eine Klasse der fehlenden Abhängigkeit zu laden.
Force-Load ruft direkt den `PluginLoader` auf und umgeht das Ergebnis der `PluginSecurityStrategy`-Kette (IP-04) gezielt für genau ein Plugin; der Aufruf muss protokolliert werden (Plugin-ID, ursprünglicher Fehlschlaggrund der Sicherheitsprüfung), damit der Vorgang im Log nachvollziehbar bleibt (siehe Log-Level-Konzept in Abschnitt 4).

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

`PluginLoader` ist vollständig von `PluginScanResult` und damit vom `scanner`-Package entkoppelt (im Dialog mit dem Nutzer entschieden): `load(path: Path, manifest: PluginManifest, dependencies: Map<String, LoadedPlugin> = emptyMap()): PluginLoadResult` nimmt nur den Kandidatenpfad und das bereits geparste Manifest entgegen, kennt weder `PluginScanStatus` noch `PluginLocation` noch `errorMessage`. Kein `PluginLoadMode`-Enum und kein separater `scanStrategy`-Parameter: der Lademodus (Single-JAR/Multi-JAR-Ordner/ZIP) wird direkt aus `path` abgeleitet (`Files.isDirectory`, `.zip`-Endung, sonst Single-JAR). Kein separater Force-Load-Einstiegspunkt und - nach erneuter Rücksprache mit dem Nutzer - auch kein `forceLoadReason`-Parameter mehr (ursprünglich `forceLoad(...)`, dann ein optionaler Log-Parameter an `load`, beides verworfen): `load` prüft den Sicherheitsstatus überhaupt nicht und ist bedingungslos aufrufbar, unabhängig von einem etwaigen vorherigen `SECURITY_PROBLEM`. Ob und wann ein Plugin trotz gescheiterter Sicherheitsprüfung geladen wird, UND die Protokollierung dieser Entscheidung, liegen vollständig und ausschließlich beim Aufrufer (Host-Anwendung bzw. IP-07) - `PluginLoader` selbst loggt dazu nichts. SDK-Whitelist als `List<SdkWhitelistEntry>` (`packageName: String`, `recursive: Boolean = true`) statt einer einfachen `List<String>`, damit pro Eintrag zwischen rekursiver Paketfreigabe und ausschließlich direkten Klassen unterschieden werden kann; `java.*`/`javax.*`-Klassen werden davon unabhängig immer unbedingt an `ClassLoader.getPlatformClassLoader()` delegiert (sonst wäre bereits `java.lang.Object` für jedes Plugin unauflösbar). Neu gegenüber dem ursprünglichen Plan, im Dialog mit dem Nutzer ergänzt: ein `PluginDependencyStrategy`-Interface (`fun isVisible(from: Path, to: Path): Boolean`, analog zu `PluginSecurityStrategy` aus IP-04) steuert zusätzlich zur reinen Abhängigkeitsdeklaration im Manifest, ob Plugins EINES Orts Plugins eines ANDEREN Orts überhaupt als Abhängigkeit sehen dürfen - global konfiguriert, mit optionalem Override je `PluginLocation`. Mitgeliefert: `UnrestrictedPluginDependencyStrategy` (Standard, jeder Ort sieht jeden anderen), `LocationPluginDependencyStrategy(allowedLocations: Set<Path>)` (eigener Ort plus explizit erlaubte Orte) und `DisallowPluginDependencyStrategy` (unterbindet Abhängigkeiten grundsätzlich, auch innerhalb desselben Orts - die einzige Ausnahme von der sonst festen Regel "ein Ort sieht sich immer selbst"). Dafür wurde `PluginLocation` (IP-03) nachträglich um `dependencyStrategyOverride: PluginDependencyStrategy? = null` erweitert, analog zu `securityOverride`. Eine nicht sichtbare Abhängigkeit wird von `DependencyGraph.topologicalOrder` und `PluginLoader` wie eine fehlende Abhängigkeit behandelt (`required` → Plugin ungültig, `optional` → ignoriert). `DependencyGraph.topologicalOrder` zählt `required`- und `optional`-Kanten gleichermaßen für die Zyklenerkennung, wie ursprünglich geplant.

**Nachträgliche Erweiterung durch IP-06 (ClassLoader-Schließung)**

`PluginLoader` erhält in IP-06 zusätzlich die Fähigkeit, einen zuvor erzeugten Plugin-ClassLoader wieder zu schließen/zu verwerfen, da eine Deaktivierung ab IP-06 immer ein vollständiges Entladen des ClassLoaders bedeutet (nicht nur ein Ausblenden der Extensions).

### IP-06: Lifecycle & Fehlerisolation (COMPLETED)

**Ziel**

Verwaltet den Lebenszyklus jedes Plugins (Laden, Aktivieren, Deaktivieren, Entladen inkl. ClassLoader-Schließung) sowie den persistenten Enabled/Disabled-Status, die Isolation von Laufzeitfehlern einzelner Plugins über eine Proxy-basierte `ExceptionHandlingStrategy`, den zentralen `PluginManager`-Einstiegspunkt und ein generalisiertes Persistence-Modul.

**Umfang**

Enthält: optionales `PluginLifecycle`-Interface (`onLoad`/`onEnable`/`onDisable`/`onUnload`, Aufrufreihenfolge dient der Ladereihenfolge bei Abhängigkeiten, nicht der Enabled/Disabled-Abbildung; Nicht-Determinismus bei mehreren Implementierungen); Deaktivierung schließt immer den Plugin-ClassLoader, Reaktivierung erfordert vollständigen Neu-Ladevorgang inkl. Security-Re-Check. Neues Persistence-Modul (`PluginPersistenceStrategy`-Interface, generisches Key-Value-Modell je Plugin-ID, mitgeliefert `NoPersistenceStrategy`/`CustomPersistenceStrategy`/`FilePersistenceStrategy`/`DatabasePersistenceStrategy`), genutzt für Enabled/Disabled-Status UND als Migration der Checksum-Persistenz aus IP-04. Enabled/Disabled-Status-Prüfung VOR dem Extension-Decorator-Mapping, damit Extension-Klassen deaktivierter Plugins gar nicht erst geladen werden. Umbenennung `PluginSecurityChainEvaluator` → `PluginSecurity` (öffentliche API analog `PluginLoader`) inkl. Security-Re-Check-Ablauf bei Reaktivierung (`PluginSecurity` → `PluginLoader`). Laufzeit-Fehlerisolation über konfigurierbare `ExceptionHandlingStrategy` (`IGNORE`/`UNLOAD`/`CRASH`, Klassenhierarchie-Lookup, `parent`-Verschachtelung, Standard-Matrix inkl. mitgelieferter `PluginExecutionException`/`PluginFatalException`), durchgesetzt über einen Proxy (JDK-Proxy für Interface-`T`, ByteBuddy-Subklasse für offene Klassen, neue Dependency `net.bytebuddy:byte-buddy`+`org.objenesis:objenesis`) inkl. rekursivem Wrapping proxy-eligibler Rückgabewerte (auch Arrays/`Collection`/`Map`-Values). Neuer zentraler Einstiegspunkt `PluginManager` (Root-Paket, Kotlin-Builder-DSL) als Konfigurations-/Zugriffs-Fassade auf `scanner`/`security`/`loader`.
Enthält nicht: Instanziierung der Extension-Implementierung selbst (IP-02, jetzt über Proxy ausgeliefert), vollständigen Orchestrierungsablauf/ID-Kollision/minVersion (IP-07).

**Betroffene Bereiche**

Neues Lifecycle-Modul, neues Persistence-Modul, Erweiterung des Security-Moduls (Umbenennung/Re-Check), Erweiterung des Extension-Moduls (IP-02, Proxy-Auslieferung), Erweiterung des ClassLoader-Moduls (IP-05, Schließen), neues PluginManager-Modul im Root-Paket.

**Abhängigkeiten**

IP-02 (Extension-Instanzen als Ziel der Hooks/Proxy), IP-05 (ClassLoader-Zugriff für Schließen/Reaktivierung).

**Erwartetes Ergebnis**

Ein Plugin durchläuft beim Laden/Entladen nachvollziehbar seine Lifecycle-Phasen; ein deaktiviertes Plugin bleibt zwar bekannt/gescannt, lädt aber keine Extension-Klassen und besitzt keinen aktiven ClassLoader mehr. Eine zur Laufzeit von einer Extension nach außen dringende Exception wird gemäß `ExceptionHandlingStrategy` behandelt; `UNLOAD` deaktiviert dauerhaft nur das betroffene Plugin, die übrige Anwendung bleibt unbeeinträchtigt. Reaktivierung ist nur nach erneut erfolgreicher `PluginSecurity`-Prüfung möglich. `PluginManager` liefert vorkonfigurierte Kernkomponenten.

**Technische Hinweise**

Die Zwangsdeaktivierung nach einem Laufzeitfehler gilt dauerhaft und übersteht auch einen Neustart, bis das Plugin manuell reaktiviert wird; sie nutzt denselben `PluginPersistenceStrategy`-Mechanismus wie eine bewusste Nutzer-Deaktivierung, ist aber über einen eigenen Grund unterscheidbar.
Ein Plugin kann das Exception-Handling nur indirekt über die Wahl der geworfenen Exception-Klasse beeinflussen, nicht über eine eigene registrierte Strategie — es gibt ausschließlich eine host-weite `ExceptionHandlingStrategy`.
Jeder Typ, der die Plugin-Grenze über eine proxyte Methode überschreiten kann, sollte selbst proxy-eligibel (Interface oder offene Klasse) sein, sonst greift die Fehlerisolation an dieser Stelle nicht (dokumentierte Design-Regel für Host-APIs, mit WARN-Log zur Laufzeit).
**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

`PluginExtensionCandidate` (IP-02) erhielt statt eines separaten Enabled/Disabled-Callback-Objekts nur ein
zusätzliches `onUnload: () -> Unit`-Feld; `ExtensionAggregator` selbst prüft den Enabled/Disabled-Status,
instanziiert den Enforcement-Proxy und ruft bei `UNLOAD` sowohl `PluginLifecycle`-Hooks als auch die
Persistence-Writes und diesen Callback auf. Der `ExceptionHandlingStrategy`-Standard-Matrix-Fallback
unterscheidet checked/unchecked NICHT über generische `Exception`/`Throwable`-Matrixeinträge (eine
`RuntimeException` hätte sonst fälschlich die `Exception`-Regel geerbt), sondern über eine explizite
`is Exception && !is RuntimeException`-Prüfung; `PluginExecutionException`/`PluginFatalException` sind
bewusst `RuntimeException` statt `Exception`, da ein JDK-`Proxy` eine checked Exception sonst in
`UndeclaredThrowableException` verpackt hätte. Abweichung vom Plan-Wortlaut: ein `T` mit fehlender
Proxy-Eligibilität blockiert das Laden betroffener Plugins NICHT automatisch (nur ERROR-Log bei
Registrierung in `ExtensionPointRegistry`) - eine echte Ladeblockade hätte eine zusätzliche
Ablehnungsprüfung in `ExtensionAggregator.aggregate` erfordert, die über die eigentliche
Proxy-Durchsetzung hinausgegangen wäre; stattdessen wird die reale, ungeschützte Instanz durchgereicht.
`DependencyGraph.topologicalOrder` (IP-05) wurde NICHT um eine "Informieren bei UNLOAD"-Fähigkeit
erweitert, da es sich um eine bewusst zustandslose, reine Funktion ohne Laufzeitinstanz handelt - die
Ladereihenfolge wird bei jedem Aufruf ohnehin frisch aus der übergebenen Manifest-Liste berechnet, ein
entferntes Plugin fällt beim nächsten Aufruf einfach aus dieser Liste heraus. Neue Test-Dependency
`com.h2database:h2` (EPL-1.0) für den In-Memory-JDBC-Test von `DatabasePersistenceStrategy`, mit dem
Nutzer abgestimmt. Details siehe Notiz zu IP-06 in `.claude/plans/features/FP-001-PluginManagementSystem-status.md`.

### IP-07: Orchestrierung & Laufzeit-Runtime (COMPLETED)

**Ziel**

Vervollständigt den bereits in IP-06 eingeführten `PluginManager` zu einem vom Host steuerbaren Gesamtablauf inklusive ID-Kollisionsauflösung, `minVersion`-Prüfung und einem Force-Load-Einstiegspunkt.

**Umfang**

Enthält: Gesamtsteuerung des Ladevorgangs über mehrere Orte mit synchron aufrufbaren Einstiegspunkten auf `PluginManager`, ID-Kollisionsauflösung (Log-Warnung, Versionsvergleich, Checksum-Vergleich, Sicherheitswarnung bei Ungleichheit), `minVersion`-Prüfung gegen Host-Version, finale Ergebnisstruktur (geladene Plugins, Extensions, Fehler, Pending-Liste, deaktivierte Plugins), gezielter Reload einzelner Plugins nach Freigabe oder nach Reaktivierung, sowie einen öffentlichen Force-Load-Einstiegspunkt, der für ein einzelnes, an der Sicherheitsprüfung gescheitertes Plugin die `PluginLoader`-Instanziierung (IP-05) explizit auf Wunsch des Frameworks-Nutzers anstößt und den Vorgang protokolliert.
Enthält nicht: UI-Darstellung der Ergebnisse (liegt beim Framework-Nutzer), jegliche Nebenläufigkeits-/Async-Infrastruktur (liegt beim Host), die Entscheidung darüber, WANN ein Force-Load fachlich gerechtfertigt ist (liegt beim Framework-Nutzer), Konfigurations-DSL selbst (bereits IP-06, `PluginManager`).

**Betroffene Bereiche**

Orchestrierungs-/Runtime-Modul, Erweiterung von `PluginManager` (IP-06) um den Gesamtablauf.

**Abhängigkeiten**

IP-04, IP-06 (insbesondere `PluginManager`, `PluginSecurity`, `PluginPersistenceStrategy`).

**Erwartetes Ergebnis**

Der Framework-Nutzer kann das Gesamtsystem mit einer Liste von Orten starten (synchron aufrufbare API), das vollständige Ergebnis erhalten (inkl. Fehlern, Pending- und deaktivierten Plugins) und nach Nutzerfreigabe bzw. Reaktivierung gezielt einzelne Plugins nachladen; wie der Host diese Aufrufe zeitlich einbettet (Thread, Coroutine, Executor), bleibt allein seine Entscheidung. Zusätzlich kann der Framework-Nutzer für ein konkretes, an der Sicherheitsprüfung gescheitertes Plugin explizit einen Force-Load auslösen, dessen Durchführung nachvollziehbar protokolliert wird.

**Technische Hinweise**

Keine framework-eigene Async-API — die öffentliche API besteht aus normalen (ggf. blockierenden) Funktionsaufrufen, die der Host bei Bedarf selbst in einen eigenen Thread/Coroutine/Executor auslagert.
Der Force-Load-Einstiegspunkt nimmt die Plugin-ID (bzw. das zuvor im Scan-/Sicherheitsergebnis identifizierte Plugin) entgegen und delegiert intern an den `PluginLoader` aus IP-05; er verändert nicht das ursprüngliche Sicherheitsergebnis im Scan-Ergebnis, sondern ergänzt es um den Hinweis, dass das Plugin zusätzlich per Force-Load geladen wurde.

**Tatsächliche Umsetzung (Abweichungen vom ursprünglichen Plan)**

Im Dialog mit dem Nutzer wurde `PluginManager` bewusst zustandsbehaftet gestaltet statt ein
zusammengesetztes Ergebnis-Objekt zurückzugeben: `scan()`/`reload(pluginId)`/`unload(pluginId)`/
`forceLoad(pluginId)` mutieren die Properties `scanResults`/`loadedPlugins`/`extensionsByKey` direkt
auf der Instanz (intern durch ein `ReentrantLock` abgesichert), der Host liest den jeweils aktuellen
Zustand direkt vom `PluginManager` statt aus einem Rückgabewert. Wiederverwendung bestehender
Modelle statt neuer Wrapper-Klassen: `PluginScanStatus` (IP-03) wurde um `ID_COLLISION`,
`MIN_VERSION_VIOLATION` und `LOAD_FAILED` erweitert, `PluginScanResult` bleibt die einzige Struktur
für Ablehnungsgründe; `PluginScanner.applySecurityCheck` (IP-03/04) setzt das Manifest bei
`SECURITY_PROBLEM` seit IP-07 nicht mehr auf `null`, damit ein Force-Load weiterhin Zugriff darauf
hat. ID-Kollisionsauflösung ohne Checksum-Vergleich als Tie-Breaker (auf ausdrücklichen
Nutzerwunsch, abweichend vom Wortlaut oben): bei Versionsgleichheit wird die gesamte Gruppe sofort
und ausnahmslos abgelehnt. Zwei neue Mechanismen, um einen Force-Load dauerhaft zu machen: ein
`PersistableSecurityStrategy`-Interface (`persist(pluginId, result)`), implementiert von
`ChecksumSecurityStrategy` (IP-04) und aufgerufen über `PluginManager.write<T>(pluginId)`; sowie ein
generischer, dauerhafter Security-Exception-Mechanismus für nicht-persistierbare Strategien
(`PluginSecurity.SECURITY_EXCEPTION_KEY`, zentral geprüft in `PluginSecurity.evaluate` (IP-04) vor
der Kette, gesetzt über `forceLoad(pluginId, persistException = true)`) - dafür erhielt
`PluginSecurity` einen `persistenceStrategy`-Konstruktorparameter. `ExtensionAggregator` (IP-02/06)
wurde um `classResolverFor: (pluginId) -> ExtensionClassResolver` (statt eines einzelnen
`classResolver`) sowie das interne `ExtensionAggregationResult.realInstancesByPlugin` erweitert,
damit `unload` Zugriff auf die realen (ungeproxten) Instanzen für die Lifecycle-Hooks hat. Die
Builder-DSL (`PluginManagerConfiguration`, IP-06) wurde durchgängig auf verschachtelte Blöcke
umgestellt (`location { ... }`, `securityOverride { addStrategy(...) }`, `defaultSecurityChain {
type = ...; addStrategy(...) }`, `sdkWhitelistEntry { ... }`) statt vorgefertigter Werte/Listen als
Argument, plus eine neue `extensionPoint(KClass)`-Funktion/`extensionPointClasses`-Liste - `registry:
ExtensionPointRegistry` wird von `PluginManager` einmalig selbst daraus gebaut. Typisierter
Extension-Zugriff über `getExtensions<T>(key)`/`getFirstExtension<T>(key)` statt direktem Zugriff auf
`extensionsByKey`.

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
│   └── IP-06 (COMPLETED)
│       └── IP-07 (COMPLETED)
├── IP-03 (COMPLETED)
│   ├── IP-04 (COMPLETED, Persistenz-Migration in IP-06)
│   │   ├── IP-07 (COMPLETED)
│   │   └── IP-08
│   └── IP-05 (COMPLETED, Erweiterung in IP-06)
│       └── IP-06 (COMPLETED)
```

## 9. Risiken und offene Fragen

* Genaue Konfigurationsschnittstelle für die vom Host bereitgestellte SDK-Whitelist (Format, Granularität: Paket- vs. Klassenebene) ist in der Detailplanung von IP-05 festzulegen
* Geklärt (IP-06): Deaktivierungsgrund (Nutzer vs. Laufzeitfehler) wird über einen eigenen Key in der `PluginPersistenceStrategy` unterschieden
* Geklärt: `PENDING_APPROVAL` existiert nicht als Framework-Zustand; jede Strategie liefert nur Erfolg/Fehlschlag, ein Gesamtfehlschlag der Kette wird als `PluginScanStatus.SECURITY_PROBLEM` gemeldet, eine Freigabe ist reine Host-Entscheidung außerhalb von IP-04
* Bibliotheksauswahl für RFC-9580-konformes OpenPGP-Parsing ist offen und mit dem Nutzer gemäß `dependencies.md` abzustimmen
* Zeitverhalten (Timeout, Caching) und Fehlerbehandlung des OpenPGP-Keyserver-Zugriffs bei Netzwerkausfall sind in der Detailplanung von IP-08 zu klären
* Geklärt: Force-Load benötigt keinen eigenen Audit-Trail-Callback; die Verantwortung für korrektes Logging des Vorgangs liegt bei der Host-Anwendung
* Geklärt: Force-Load benötigt kein vom Host konfigurierbares generelles An/Aus; der Aufruf ist ein reiner, von der Host-Implementierung selbst gerufener API-Aufruf, eine zusätzliche Sperre wäre wirkungslos (der aufrufende Code kann sich nicht spontan selbst ändern)
* Geklärt (IP-06): Es gibt ausschließlich eine host-weite `ExceptionHandlingStrategy`; ein Plugin kann das Handling nur indirekt über die Wahl der geworfenen Exception-Klasse beeinflussen, nicht über eine eigene registrierte Strategie
* Geklärt (IP-06): Für konkrete Rückgabetypen (nicht nur `T` selbst) wird ByteBuddy statt CGLib genutzt (aktiver gepflegt, bessere JDK-25-Kompatibilität); finale Klassen/Methoden sowie direkter Feldzugriff bleiben grundsätzlich nicht proxybar (Java-Sprachregel)

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
* Ein über `PluginPersistenceStrategy` dauerhaft deaktiviertes Plugin lädt keine Extension-Klassen und besitzt keinen aktiven ClassLoader, bleibt aber im Scan-Ergebnis sichtbar
* Eine aus einem Extension-Aufruf nach außen dringende Exception wird gemäß `ExceptionHandlingStrategy` behandelt; `UNLOAD` deaktiviert automatisch nur das betroffene Plugin dauerhaft (muss manuell reaktiviert werden), ohne die übrige Anwendung zu beeinträchtigen
* Jede an den Host ausgelieferte Extension-Instanz ist eine Proxy-Instanz; aus dem Host-Plugin-API dringen ausschließlich `PluginExecutionException`/`PluginFatalException` nach außen, nie eine rohe Plugin-Exception
* Eine Reaktivierung eines deaktivierten Plugins führt nur nach erneut erfolgreicher `PluginSecurity`-Prüfung zu einem tatsächlichen Neu-Laden über `PluginLoader`
* Befüllen zwei Plugins denselben exklusiven Extension-Point, werden beide vollständig nicht geladen und eine nachvollziehbare Log-Warnung ausgegeben
* Die öffentliche API des Frameworks lässt sich sowohl blockierend als auch aus einem vom Host gewählten nebenläufigen Kontext heraus verwenden
* Ein Plugin mit fehlgeschlagener Sicherheitsprüfung kann über einen expliziten Force-Load-Aufruf des Hosts dennoch geladen werden, wobei der Vorgang nachvollziehbar protokolliert wird
* `PluginManager` liefert per Kotlin-Builder-DSL vorkonfigurierte `scanner`/`security`/`loader`-Instanzen aus einer einzigen host-weiten Konfiguration
