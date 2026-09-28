# Feature Plan: GraalVM Native-Image-Kompatibilität

## 1. Objective

pluggiat soll optional als GraalVM-`native-image` in einem Host lauffähig sein, **ohne** die
bestehende Nutzung auf einer normalen JVM (inkl. GraalVM als reine JIT-Distribution) in
irgendeiner Form einzuschränken oder zu verändern. Es handelt sich nicht um eine Migration,
sondern um eine zusätzliche, rein additive Unterstützung für einen zweiten Deployment-Modus.

## 2. Current State

* pluggiat ist eine Bibliothek (ein Gradle-Modul, `build.gradle.kts`), die in einen Host
  eingebettet wird - kein eigenständiges ausführbares Programm. Ein natives Image wird also vom
  **Host** gebaut, nicht von pluggiat selbst; pluggiat kann nur die dafür nötigen Voraussetzungen
  (Reachability-Metadata, unterstützte Codepfade) bereitstellen.
* Drei Mechanismen sind mit GraalVM `native-image`s Closed-World-Annahme (keine zur Build-Zeit
  unbekannten Klassen, kein späteres dynamisches Nachladen) grundsätzlich unvereinbar:
  1. `PluginClassLoader`/`PinnedPluginClassLoader` (`src/main/kotlin/.../classloader/`) laden
     Plugin-JARs/-ZIPs dynamisch von der Platte, deren konkrete Klassen erst zur Laufzeit bekannt
     sind - das genaue Gegenteil von Closed-World.
  2. Die In-VM-Sandbox (`AgentInstrumentationStrategy`,
     `sandbox/agent/PluginSandboxAgent.kt`) instrumentiert Plugin-Bytecode zur Laufzeit über
     `java.lang.instrument.Instrumentation`, aktiviert per `-javaagent`-Parameter - `native-image`
     unterstützt Java-Agents zur Laufzeit nicht.
  3. Jackson (`jackson-dataformat-yaml`, `jackson-module-kotlin`, siehe
     `manifest/ManifestParser.kt`) verwendet Reflection, die `native-image` ohne vorab erzeugte
     Reachability-Metadata nicht auflösen kann - auch für host-eigene, zur Build-Zeit bekannte
     DTOs wie `PluginManifest`.
  4. Auch der Aggregations-/Proxy-Mechanismus über Byte Buddy
     (`net.bytebuddy:byte-buddy`, siehe `ExtensionDecorator.kt`) erzeugt zur Laufzeit neue Klassen
     - mit `native-image` nur über dessen eigene, eingeschränkte Proxy-/Agent-Unterstützung
     nutzbar, nicht in der bestehenden Form.
* `ProcessIsolationStrategy` (`sandbox/process/ProcessIsolationStrategy.kt`, FP-002/IP-04) ist
  bereits vollständig implementiert: ein Plugin läuft in einem eigenen JVM-Subprozess und
  kommuniziert ausschließlich über ein ASN.1-BER-TLV-Protokoll (Bouncy Castle) auf einem
  `java.net`-Socket (`sandbox/process/ber/BerCodec.kt`, `PluginProcessManager.kt`). Laut KDoc in
  `ProcessIsolationStrategy` wird ein prozessisoliertes Plugin dennoch weiterhin **im Host**
  über den regulären `PluginLoader`-Pfad geladen (für Manifest-Parsing, Dependency-Resolution,
  Extension-Point-/Konfigurations-Mapping) - nur die Instanziierung der
  Extension-Implementierungsklassen entfällt dort. Dieses In-Host-Classloading des Plugin-JARs
  bliebe unter `native-image` weiterhin ein Blocker, auch im Prozessisolations-Modus.
* Es existiert weder eine GraalVM-Reachability-Metadata-Auslieferung noch ein CI-Job, der
  `native-image`-Kompatibilität der Bibliothek selbst prüft.
* Build ist `jvmToolchain(25)`, ein einzelnes Gradle-Modul, kein GraalVM-Plugin eingebunden.

## 3. Target State

* Ein Host kann pluggiat weiterhin unverändert auf jeder normalen JVM (inkl. GraalVM als
  JIT-Distribution) nutzen - alle drei Sandbox-/Lade-Modi (In-VM ohne Sandbox, In-VM mit
  Agent-Sandbox, Prozessisolation) funktionieren exakt wie heute, ohne Konfigurationsänderung.
* Ein Host, der selbst als GraalVM-`native-image` gebaut wird, kann pluggiat **ausschließlich im
  Prozessisolations-Modus** einbetten, sofern der host-eigene, zur Build-Zeit bekannte Code-Anteil
  von pluggiat (Konfiguration, Manifest-DTOs, `PluginManager`-Fassade, IPC-Client-Seite) unter
  `native-image` lauffähig ist. Der Plugin-Subprozess selbst bleibt eine normale (nicht-native)
  JVM und ist von dieser Einschränkung nicht betroffen.
* pluggiat liefert im veröffentlichten JAR eigene GraalVM-Reachability-Metadata
  (`META-INF/native-image/org.pcsoft.framework/pluggiat/*.json`) für seine host-seitig zur
  Build-Zeit bekannten reflektiven Zugriffe (Manifest-DTOs via Jackson) aus, sodass ein Host beim
  `native-image`-Build diese nicht selbst von Hand nachpflegen muss.
* Ein Host, der versucht, unter `native-image` einen nicht unterstützten Modus zu aktivieren (reines
  In-VM-Classloading eines Plugins, agent-basierte In-VM-Sandbox), erhält dafür standardmäßig eine
  frühe, eindeutige Fehlermeldung statt eines undefinierten Laufzeitfehlers oder eines stillen
  Fehlverhaltens - kann dies aber pro betroffener Strategie über ein explizites Opt-in-Feld
  (`forceOnNativeImage`, Default `false`) bewusst erzwingen.
* Die Einschränkungen und der unterstützte Modus sind im MkDocs-Sicherheits-/Betriebskapitel
  dokumentiert (Bezug zum bereits vorhandenen Sicherheitshinweis auf der Hauptseite).

## 4. Requirements

### Functional Requirements

* Bestehende Hosts auf einer normalen JVM sind von diesem Feature funktional nicht betroffen -
  keine Verhaltensänderung, kein neuer Pflicht-Konfigurationsschritt.
* pluggiat erkennt selbst, ob der Host unter `native-image` läuft, und lehnt eine nicht
  unterstützte Sandbox-/Lade-Konfiguration (In-VM-Classloading eines Plugins,
  `AgentInstrumentationStrategy`) mit einer aussagekräftigen Exception ab, statt fehlzuschlagen
  oder falsches Verhalten zu zeigen - sofern die betroffene Strategie nicht explizit über
  `forceOnNativeImage = true` von diesem Schutz ausgenommen wurde.
* Ein Host, der `native-image` nutzt und ausschließlich Prozessisolation konfiguriert, kann
  pluggiat mit demselben `PluginManager`/`PluginSandbox`-API wie auf der normalen JVM verwenden.
* pluggiats eigene, host-seitig zur Build-Zeit bekannte reflektive Zugriffe (Jackson-Manifest-DTOs)
  sind über mitgelieferte Reachability-Metadata für einen Host-`native-image`-Build ohne
  manuelle Nacharbeit auflösbar.

### Technical Requirements

* Keine Änderung an den bestehenden In-VM-Lade-/Sandbox-Pfaden für normale JVM-Nutzung; das
  Feature ist rein additiv (neue Metadata-Dateien, ein neuer Laufzeit-Check, ein neuer,
  optionaler CI-Verifikationsschritt).
* Reachability-Metadata wird ausschließlich für pluggiats eigene, zur Build-Zeit bekannte
  Klassen erzeugt (Manifest-DTOs, interne Konfigurationstypen) - niemals für Plugin-Klassen, die
  grundsätzlich erst zur Laufzeit bekannt sind und unter `native-image` prinzipiell nicht erfasst
  werden können.
* Die Erkennung, ob der Host unter `native-image` läuft, nutzt die GraalVM-SDK-Bibliothek
  `org.graalvm.sdk:nativeimage` (`ImageInfo.inImageCode()`) als neue, vom Nutzer bestätigte
  Fremdabhängigkeit (siehe `dependencies.md`, Abschnitt 9).
* Keine Änderung an `ProcessIsolationStrategy`s bestehendem IPC-Protokoll (Bouncy-Castle-ASN.1-BER
  auf `java.net`-Sockets); der Plugin-Subprozess bleibt eine normale JVM.
* Ein CI-Verifikationsschritt (siehe `ci-pipeline`-Skill vor Änderung an Workflow-Dateien) baut
  probeweise ein minimales natives Test-Host-Image gegen pluggiats Prozessisolations-Modus, um
  Reachability-Metadata-Regressionen zu erkennen - ohne den bestehenden `build`-Task oder dessen
  Laufzeit auf der normalen JVM zu verändern.
* **Jeder Implementation Plan liefert für sich einen durch die reguläre Testsuite (auf normaler
  JVM, ohne echten `native-image`-Build) verifizierten, funktionsfähigen Zwischenstand.** IP-01
  und IP-02 müssen daher so geschnitten sein, dass ihr jeweiliges Ergebnis mit gewöhnlichen
  JUnit-Tests abgesichert werden kann; der schwergewichtige, echte `native-image`-Build (IP-03)
  ist eine zusätzliche End-to-End-Absicherung, nicht die einzige Stelle, an der das neue
  Verhalten geprüft wird.

## 5. Architecture

* Kein neues Gradle-(Sub-)Modul für pluggiat selbst - die Bibliothek bleibt ein einzelnes Modul.
  Für den CI-Verifikationsschritt (IP-03) wird lediglich ein separates, minimales Test-Host-Setup
  benötigt (z. B. unter `src/nativeImageTest/` oder als eigenständiges Beispielprojekt), das
  ausschließlich zu Verifikationszwecken dient und nicht Teil des veröffentlichten Artefakts ist.
* Neues Verzeichnis `src/main/resources/META-INF/native-image/org.pcsoft.framework/pluggiat/`
  mit `reflect-config.json` (und ggf. `resource-config.json`) für die Jackson-basierten
  Manifest-DTOs (`manifest/ManifestParser.kt` und zugehörige Datenklassen) - wird automatisch Teil
  des JARs und von GraalVM beim `native-image`-Build eines Hosts, der pluggiat als Abhängigkeit
  einbindet, über den Standard-Mechanismus (`native-image` liest JAR-mitgelieferte Metadata)
  aufgegriffen.
* Neue, kleine Fassaden-Funktion/-Klasse (z. B. `org.pcsoft.framework.pluggiat.NativeImageSupport`),
  die zentral prüft, ob der Host aktuell unter `native-image` läuft (via
  `org.graalvm.nativeimage.ImageInfo.inImageCode()`, siehe Abschnitt 9).
  `PluginManager`/`PluginSandbox` fragen diese Fassade an genau den Stellen ab, an denen heute
  bereits vergleichbare Voraussetzungsprüfungen stattfinden (analog zu
  `SandboxAgentNotActiveException` in `AgentInstrumentationStrategy`/`ProcessIsolationStrategy`),
  und werfen eine neue, eigene Exception (z. B. `NativeImageUnsupportedModeException`), wenn die
  aktive Konfiguration In-VM-Classloading oder Agent-Instrumentierung unter `native-image`
  verlangt. `NativeImageSupport` kapselt den `ImageInfo.inImageCode()`-Aufruf hinter einem
  austauschbaren Seam (z. B. einer internen, zur Testzeit überschreibbaren Funktion/Property),
  damit reguläre JUnit-Tests auf normaler JVM "läuft unter `native-image`" simulieren können, ohne
  einen echten nativen Build zu benötigen.
* Jede unter `native-image` grundsätzlich nicht sinnvoll nutzbare Strategie
  (`AgentInstrumentationStrategy` für die In-VM-Sandbox, der In-VM-Classloading-Pfad in
  `PluginLoader`/`PluginClassLoader`) erhält ein neues, optionales Konstruktor-/Konfigurationsfeld
  (Arbeitsname `forceOnNativeImage: Boolean = false`), analog zum bestehenden expliziten
  Opt-in-Charakter von `InsecureSecurityStrategy`. Der neue Laufzeit-Guard (siehe oben) wirft die
  `NativeImageUnsupportedModeException` nur, wenn `NativeImageSupport` "läuft unter
  `native-image`" **und** dieses Feld `false` ist (Default). Steht es auf `true`, wird die Strategie
  unverändert ausgeführt - der Host übernimmt damit bewusst die Verantwortung für ein von
  pluggiat nicht getestetes/nicht unterstütztes Verhalten (z. B. weil er über ein eigenes,
  kompatibles natives Setup verfügt oder das Risiko bewusst eingeht). Das Verhalten auf einer
  normalen JVM ist von diesem Feld vollständig unberührt (der Guard greift dort nie).
* Keine Änderung an `PluginSandbox`, `PluginSandboxStrategy`, `ProcessIsolationStrategy` selbst
  außer dem zusätzlichen frühen Check; ihre bestehende API-Oberfläche bleibt unverändert.
* MkDocs: neuer Abschnitt (`docs/docs/host-integration/` oder als eigene Seite
  `graalvm.md`/`.de.md`) zur GraalVM-`native-image`-Nutzung - unterstützter Modus
  (ausschließlich Prozessisolation), Einschränkungen, Verweis auf den bestehenden
  Sicherheitshinweis auf der Hauptseite.

## 6. Implementation Plan Overview

| ID    | Implementation Plan                              | Objective                                                                 | Dependencies |
| ----- | ------------------------------------------------- | -------------------------------------------------------------------------- | ------------ |
| IP-01 | Reachability-Metadata für Manifest-Parsing        | GraalVM-Metadata für Jackson-basierte, host-bekannte DTOs ausliefern      | -            |
| IP-02 | Laufzeit-Guard gegen nicht unterstützte Modi      | Frühe, eindeutige Fehlermeldung bei In-VM-Modi unter `native-image`       | -            |
| IP-03 | CI-Verifikation & Dokumentation                    | Natives Test-Host-Image gegen Prozessisolation bauen; MkDocs-Kapitel       | IP-01, IP-02 |

## 7. Implementation Plans

### IP-01: Reachability-Metadata für Manifest-Parsing

**Objective**

pluggiats eigene, host-seitig zur Build-Zeit bekannte reflektive Zugriffe (Jackson-Deserialisierung
der Manifest-DTOs) sind für einen Host-`native-image`-Build ohne manuelle Nacharbeit auflösbar.

**Scope**

* Erzeugung/Pflege der `reflect-config.json` (ggf. `resource-config.json`) unter
  `src/main/resources/META-INF/native-image/org.pcsoft.framework/pluggiat/` für alle
  Jackson-deserialisierten Typen in `manifest/` (inkl. `SpdxLicenses.kt`, sofern reflektiv
  eingebunden).
* Ein neuer, auf normaler JVM laufender Test, der die Metadata-Datei(en) auf strukturelle
  Konsistenz mit dem tatsächlichen Code prüft (z. B.: jede in `reflect-config.json` gelistete
  Klasse existiert per `Class.forName`, jedes gelistete Feld/jeder Konstruktor existiert per
  Reflection) - erkennt Drift zwischen Metadata und Code bereits im regulären Testlauf, ohne
  einen echten `native-image`-Build zu benötigen.
* Ausdrücklich **nicht** Teil: jegliche Metadata für Plugin-eigene Klassen - diese sind zur
  Build-Zeit des Hosts grundsätzlich unbekannt und können nicht erfasst werden.

**Affected Areas**

* `src/main/kotlin/org/pcsoft/framework/pluggiat/manifest/` (nur lesend zur Analyse)
* Neues Verzeichnis `src/main/resources/META-INF/native-image/...`

**Dependencies**

* -

**Expected Result**

Ein Host, der pluggiat einbindet und selbst `native-image` baut, muss für die
Manifest-Deserialisierung keine eigene Reachability-Metadata mehr von Hand ergänzen. Der
Konsistenz-Test läuft bereits mit dem bestehenden `test`-Task auf normaler JVM grün und schützt
die Metadata unabhängig von IP-03 vor Drift.

**Technical Considerations**

* Die Metadata-Erzeugung soll nachvollziehbar/wiederholbar sein (z. B. über den GraalVM Tracing
  Agent gegen einen realen Testlauf, nicht rein manuell erraten) - Details zur genauen
  Erzeugungsmethode sind bei Umsetzung dieses Plans zu klären.
* Wirkungslos auf normaler JVM: `META-INF/native-image/...`-Dateien werden von einer normalen JVM
  ignoriert, daher keinerlei Risiko für bestehende Nutzung.

### IP-02: Laufzeit-Guard gegen nicht unterstützte Modi

**Objective**

Ein Host, der unter `native-image` eine mit `native-image` unvereinbare Konfiguration aktiviert
(In-VM-Plugin-Classloading, agent-basierte In-VM-Sandbox), erhält dafür standardmäßig eine
frühe, eindeutige Fehlermeldung statt eines undefinierten Zustands - kann dies aber über ein
explizites Opt-in-Feld pro Strategie bewusst erzwingen.

**Scope**

* Neue zentrale Erkennungsfassade für "läuft unter `native-image`".
* Neue Exception-Klasse, geworfen an den bestehenden Voraussetzungsprüfungsstellen (analog
  `SandboxAgentNotActiveException`).
* Neues optionales Feld `forceOnNativeImage: Boolean = false` an jeder betroffenen Strategie
  (`AgentInstrumentationStrategy`, In-VM-Classloading-Pfad); bei `true` unterdrückt es die neue
  Exception für genau diese Strategie, verändert aber sonst nichts an deren Verhalten.
* Neue, auf normaler JVM laufende Tests, die über den Test-Seam von `NativeImageSupport`
  "läuft unter `native-image`" simulieren und pro betroffener Strategie mindestens folgende Fälle
  abdecken: Guard wirft `NativeImageUnsupportedModeException` bei `forceOnNativeImage = false`
  (Default); Guard wirft nichts bei `forceOnNativeImage = true`; Guard wirft nichts, wenn
  `NativeImageSupport` "normale JVM" meldet, unabhängig vom Feldwert.
* Ausdrücklich **nicht** Teil: irgendeine funktionale Änderung am In-VM-Verhalten auf einer
  normalen JVM - das Feld hat dort keinerlei Wirkung.

**Affected Areas**

* Neue Datei für die Erkennungsfassade (Paketvorschlag: `org.pcsoft.framework.pluggiat`, Root-Ebene,
  analog zu bestehenden Root-Klassen wie `PluginManager`)
* `sandbox/PluginSandbox.kt`, `sandbox/AgentInstrumentationStrategy.kt` (bzw. dessen tatsächlicher
  Dateiname), `classloader/PluginLoader.kt` - jeweils nur die Stelle, an der heute bereits
  vergleichbare Voraussetzungen geprüft werden

**Dependencies**

* -

**Expected Result**

Ein `native-image`-Host mit falscher Konfiguration bekommt standardmäßig eine klare, dokumentierte
Exception statt eines stillen oder erst spät auftretenden Fehlers - kann dies aber pro Strategie
über `forceOnNativeImage = true` bewusst übersteuern. Das gesamte Guard-Verhalten (Default-Wurf,
Opt-in-Bypass, Unwirksamkeit auf normaler JVM) ist bereits mit dem bestehenden `test`-Task auf
normaler JVM grün verifiziert, unabhängig davon, ob IP-03 schon umgesetzt ist.

**Technical Considerations**

* Die Erkennung "läuft unter `native-image`" nutzt `org.graalvm.sdk:nativeimage`
  (`ImageInfo.inImageCode()`), siehe Abschnitt 9.
* Der Check darf auf einer normalen JVM keine messbare zusätzliche Kosten verursachen (einfacher
  Boolean-Check, kein Overhead im Hot Path).

### IP-03: CI-Verifikation & Dokumentation

**Objective**

Die native-image-Kompatibilität von pluggiats eigenem Code (nicht der Plugins) ist zusätzlich zu
den bereits in IP-01/IP-02 vorhandenen JVM-Unit-Tests durch einen echten `native-image`-Build
end-to-end verifiziert und für Host-Entwickler dokumentiert.

**Scope**

* Minimales Test-Host-Setup, das pluggiat ausschließlich im Prozessisolations-Modus einbindet und
  probeweise mit GraalVM `native-image` gebaut wird - als eigener, optionaler CI-Job (siehe
  `ci-pipeline`-Skill vor Änderung an Workflow-Dateien), der den bestehenden `build`-Job nicht
  verlangsamt oder verändert.
* MkDocs-Kapitel zur GraalVM-Nutzung (unterstützter Modus, Einschränkungen, Verweis auf den
  bestehenden Sicherheitshinweis auf der Hauptseite) - `project-docs`-Skill nach Umsetzung laden.
* Ausdrücklich **nicht** Teil: Unterstützung von In-VM-Modi unter `native-image` - diese bleiben
  laut Zielzustand grundsätzlich nicht unterstützt.

**Affected Areas**

* `.github/` (neuer, optionaler Workflow bzw. Job)
* `docs/docs/` (neue Seite oder neuer Abschnitt)

**Dependencies**

* IP-01, IP-02 (das zu verifizierende/dokumentierende Verhalten muss bereits existieren)

**Expected Result**

Eine Regression der `native-image`-Kompatibilität wird in CI erkannt, bevor sie einen Host
erreicht; Host-Entwickler finden eine klare Anleitung, wie sie pluggiat unter `native-image`
einsetzen können und was dabei nicht unterstützt ist.

**Technical Considerations**

* Der native-image-Build selbst ist rechenintensiv - als eigener, ggf. nicht bei jedem Push
  laufender Job auszulegen (Details bei Umsetzung, `ci-pipeline`-Skill maßgeblich).

## 8. Dependency Graph

```text
IP-01
IP-02
IP-03 (hängt von IP-01 und IP-02 ab)
```

## 9. Risks and Open Questions

* **Geklärt - neue Fremdabhängigkeit für die `native-image`-Erkennung**: Die
  GraalVM-SDK-Bibliothek `org.graalvm.sdk:nativeimage` (`ImageInfo.inImageCode()`) wurde vom
  Nutzer als neue Fremdabhängigkeit bestätigt (siehe `dependencies.md`) und wird für die
  `native-image`-Erkennung in IP-02 verwendet; keine Heuristik ohne SDK-API nötig.
* **Grundsätzliche Grenze bleibt bestehen**: Reines In-VM-Plugin-Classloading kann unter
  `native-image` **grundsätzlich nie** unterstützt werden, unabhängig vom Umsetzungsaufwand -
  das ist eine strukturelle Eigenschaft von `native-image`s Closed-World-Modell, kein lösbares
  Implementierungsdetail. Dieses Feature schafft daher keine vollständige Parallelität aller
  Modi, sondern ausschließlich Unterstützung des bereits vorhandenen Prozessisolations-Modus.
  `forceOnNativeImage` hebt diese strukturelle Grenze nicht auf, sondern verlagert die
  Verantwortung dafür bewusst auf den Host.
* **Lizenzbericht/CycloneDX-BOM**: Eine neue Fremdabhängigkeit (falls bestätigt) muss den
  bestehenden Lizenzprüfungs-Gradle-Task bestehen (siehe `dependencies.md` -
  Lizenzierungsabschnitt).
* **Umfang der Reachability-Metadata**: Falls Host-Code (nicht nur pluggiats eigene DTOs)
  zusätzliche, host-spezifische Reflection benötigt, bleibt das außerhalb des Scopes dieses
  Features - ein Host muss seine eigene, zusätzliche Metadata selbst pflegen.
* **CI-Kosten**: Ein zusätzlicher nativer Build-Job in der Pipeline erhöht CI-Laufzeit/-Kosten;
  Frequenz (jeder Push vs. nur bei Release/Tag) ist bei IP-03-Umsetzung zu klären.

## 10. Feature Completion Criteria

* Ein Host auf einer normalen JVM nutzt pluggiat exakt wie vor diesem Feature - keine
  Verhaltensänderung, kein neuer Pflichtschritt.
* Ein Host, der als GraalVM-`native-image` gebaut wird und ausschließlich Prozessisolation
  konfiguriert, kann pluggiat nachweislich (verifiziert durch den CI-Job aus IP-03) einbinden und
  Extension-Aufrufe über den Subprozess durchführen.
* Ein `native-image`-Host, der eine nicht unterstützte Konfiguration (In-VM-Classloading,
  Agent-Sandbox) ohne gesetztes `forceOnNativeImage` aktiviert, erhält dafür nachweislich eine
  frühe, eindeutige Exception statt eines undefinierten Fehlverhaltens.
* Ein `native-image`-Host, der `forceOnNativeImage = true` für eine solche Strategie setzt,
  kann diese nachweislich unverändert (ohne die neue Exception) ausführen.
* pluggiats eigene Manifest-DTOs benötigen für einen Host-`native-image`-Build keine vom Host
  selbst nachgepflegte Reachability-Metadata.
* Die Einschränkungen (kein In-VM-Modus unter `native-image`, Grund dafür, Bedeutung von
  `forceOnNativeImage`) sind im MkDocs-Kapitel dokumentiert.
* Nach Abschluss von IP-01 und IP-02 - noch vor IP-03 - ist die gesamte neue Funktionalität
  (Metadata-Konsistenz, Guard-Default, `forceOnNativeImage`-Bypass) durch reguläre JUnit-Tests
  auf normaler JVM grün verifiziert, ohne dass dafür ein echter `native-image`-Build nötig ist.
