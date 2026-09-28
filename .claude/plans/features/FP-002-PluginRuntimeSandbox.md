# Feature Plan: Plugin Runtime Sandbox (COMPLETED)

## 1. Objective

Die bestehende Sicherheitskette (`PluginSecurity`, `SignatureSecurityStrategy`,
`ChecksumSecurityStrategy`) prüft ausschließlich Herkunft und Integrität eines Plugin-Kandidaten
*vor* dem Laden. Ist ein Plugin einmal geladen, hat es über `PluginClassLoader` uneingeschränkten
Zugriff auf alles, was über die SDK-Whitelist und den Java-Plattform-Classloader erreichbar ist -
inklusive `java.io`, `java.net`, `java.lang.reflect`, `Runtime.exec`, Thread-Erzeugung ohne Limit
usw. Ziel dieses Features ist eine **Laufzeit-Sandbox**, die das Verhalten eines bereits geladenen
Plugins begrenzt, als Ergänzung zur bestehenden Vorab-Prüfung - inklusive eines Schutzes dagegen,
dass ein Plugin (oder ein Dritter mit Dateisystemzugriff) den persistenten Zustand des Frameworks
zu seinen eigenen Gunsten manipuliert, sowie einer Härtung der Checksum-/Signaturprüfung selbst
(Lücke zwischen Prüfung und Laden, Konsistenz der Zip/Jar-Interpretation, Zertifikatsgültigkeit).

## 2. Current State

* `PluginClassLoader` (parent-last, `URLClassLoader`) isoliert Plugins nur bezüglich
  Klassensichtbarkeit: Plattformklassen werden immer aufgelöst, SDK-Whitelist-Pakete werden an den
  `hostClassLoader` delegiert, alles andere kommt aus den eigenen Plugin-JARs bzw.
  Dependency-Classloadern. Es gibt keine Zugriffskontrolle auf konkrete APIs (Dateisystem, Netzwerk,
  Reflection, Prozessstart, Threads).
* `PluginLoader.load()` erzeugt den Classloader und gibt einen `LoadedPlugin` zurück; es findet
  keinerlei Instrumentierung oder Laufzeitüberwachung statt.
* `PluginManager` verwaltet den Lifecycle (`scan`, `reload`, `unload`, `forceLoad`) und ruft
  `PluginLifecycle.onEnable/onDisable/onUnload` auf den realen Extension-Instanzen auf
  (`ExtensionAggregator`), aber ohne Zeitlimit oder Fehlerisolation über einfaches
  `runCatching` hinaus.
* `PluginManager` hält bereits ein Pendant-Muster für Sicherheitsprüfungen: `val security:
  PluginSecurity` ist die einzige Anlaufstelle für die gesamte Sicherheitskette
  (`PluginManager.kt:215`). Dieses Muster ist die Vorlage für die neue Sandbox-Fassade.
* **Lücke zwischen Sicherheitsprüfung und Laden (TOCTOU)**: `PluginScanner.applySecurityCheck`
  (`PluginScanner.kt:52`) berechnet die Checksum/Signaturprüfung eines Kandidaten zum
  Scan-Zeitpunkt, indem die jeweilige `PluginSecurityStrategy` selbst die Kandidatenbytes von der
  Platte liest (`ChecksumSecurityStrategy.candidateBytes`, `SignatureSecurityStrategy`s
  `JarFile`-Zugriff). Das eigentliche Laden geschieht separat und später: `PluginManager.scan()`
  bzw. `PluginManager.reactivate()` rufen `loader.load(scanResult.path, ...)` auf, und
  `PluginLoader` liest die JAR(s) über `URLClassLoader`/`Files.newDirectoryStream`
  (`PluginLoader.kt:79`) **erneut frisch von der Platte**, ohne erneute Prüfung. Zwischen
  Hash-/Signaturprüfung und tatsächlichem Laden besteht dadurch ein Zeitfenster, in dem der
  Kandidat auf der Platte ausgetauscht werden könnte, ohne dass der geladene Inhalt dem geprüften
  entspricht.
* `ChecksumSecurityStrategy.check()` vergleicht den erwarteten mit dem berechneten Digest über
  `String.equals(ignoreCase = true)` (`ChecksumSecurityStrategy.kt:53`) statt über einen
  zeitkonstanten Vergleich.
* `SignatureSecurityStrategy.verifyJarSignature()` (`SignatureSecurityStrategy.kt:131`) vergleicht
  ausschließlich den öffentlichen Schlüssel eines Code-Signers (`signer.signerCertPath
  .certificates.firstOrNull()?.publicKey == expectedKey`). Weder Gültigkeitszeitraum
  (`notBefore`/`notAfter`) noch Widerruf (CRL/OCSP) des Zertifikats werden geprüft - ein
  abgelaufenes oder (wo relevant) widerrufenes Zertifikat wird derzeit genauso akzeptiert wie ein
  gültiges, solange der reine Schlüsselwert übereinstimmt.
* Die Signaturprüfung liest JAR-Einträge heute über `java.util.jar.JarFile`, während `PluginLoader`
  sie über `URLClassLoader` liest - beide nutzen intern `java.util.zip` und sind bislang konsistent
  in ihrer Interpretation von ZIP-Eintragsnamen. Eine geplante Änderung (Byte-Pinning, siehe unten)
  führt einen dritten, unabhängigen ZIP/JAR-Parser ein, der ohne explizite Abstimmung von dieser
  Interpretation abweichen und damit eine "verify one entry, load another"-Lücke bei doppelten
  ZIP-Eintragsnamen neu einführen könnte.
* `ExceptionHandlingStrategy` (`DefaultExceptionHandlingStrategy`) behandelt Exceptions aus
  Extension-Code, aber nicht aktiv laufende Threads, Endlosschleifen oder Ressourcenverbrauch.
* Build-Ziel ist `jvmToolchain(25)` (siehe `build.gradle.kts`). Der klassische
  `java.lang.SecurityManager`/`AccessController`-Mechanismus ist seit JDK 17 deprecated und seit
  JDK 24 vollständig entfernt (JEP 486) - er steht als Grundlage für eine In-VM-Permission-Sandbox
  **nicht** zur Verfügung.
* Dynamisches Nachladen eines Java-Agents zur Laufzeit (Attach-API) ist seit JDK 21 per Default
  eingeschränkt (JEP 451) und erzeugt ohne explizites Freischalten Warnungen bzw. wird verweigert -
  auf dem Zieltarget JDK 25 ist ein Java-Agent daher kein transparenter, rein bibliotheksseitiger
  Mechanismus mehr, sondern erfordert eine bewusste Host-Konfiguration.
* Es existiert keine Prozess- oder Modul-Isolation; alle Plugins laufen im selben JVM-Prozess wie
  der Host.
* Das JDK bietet keine öffentliche API zur ASN.1-Kodierung/-Dekodierung (nur interne,
  projektintern nicht nutzbare `sun.security`-Klassen). Der Nutzer hat vorgegeben, für die
  ASN.1-Kodierung/-Dekodierung die Fremdbibliothek **Bouncy Castle** (`org.bouncycastle:bcprov-jdk18on`,
  Klassen wie `ASN1InputStream`/`ASN1OutputStream`/`DERSequence` etc.) einzusetzen; dies ist die
  in `dependencies.md` geforderte Nutzer-Abstimmung für diese neue Fremdabhängigkeit.
* `PluginPersistenceStrategy` (`PluginPersistenceStrategy.kt:24`) ist ein generischer,
  ungeschützter Key-Value-Store (`read`/`write` pro `(pluginId, key)`) ohne jede Zugriffskontrolle
  oder Integritätsprüfung. `FilePersistenceStrategy` (`FilePersistenceStrategy.kt:37`) schreibt den
  gesamten Zustand unverschlüsselt/unsigniert in eine einzelne Datei. Solange ein Plugin
  uneingeschränkten `java.io`-Zugriff hat, kann es diese Datei direkt bearbeiten und z. B. seinen
  eigenen `checksum`-, `securityException`- oder `enabled`-Eintrag fälschen und damit die gesamte
  Sicherheitskette für sich selbst aushebeln.
* **Kollisionsauflösung ignoriert den Sicherheits-/Scan-Status**: `IdCollisionResolver.resolve()`
  (`IdCollisionResolver.kt:40`) gruppiert alle Kandidaten mit gleicher `manifest.id` **unabhängig
  von ihrem Scan-Status** (`withManifest = results.filter { it.manifest != null }`, ohne
  Status-Filter) - ein Kandidat mit Status `SECURITY_PROBLEM` behält sein Manifest bewusst (siehe
  `PluginScanner.kt:62`) und konkurriert dadurch in `resolveGroup()` weiterhin per
  `ComparableVersion` um die "beste" Version innerhalb der Gruppe. Ein Angreifer kann daher ein
  Plugin mit derselben `id` wie ein bereits legitim signiertes/checksummiertes Plugin und einer
  **höheren** deklarierten `version` platzieren, ohne die Signatur-/Checksumprüfung selbst bestehen
  zu müssen: Sein eigener Kandidat bleibt zwar wegen `SECURITY_PROBLEM` ungeladen, gewinnt aber den
  Versionsvergleich und lässt dadurch das legitime, tatsächlich verifizierte Plugin an anderer
  Location mit `ID_COLLISION` verwerfen - ein Downgrade-/Denial-of-Service-Vektor, der die
  Checksum-/Signaturprüfung vollständig umgeht, da er nicht versucht, sie zu bestehen.

## 3. Target State

* Ein Plugin, das die bestehende Sicherheitskette erfolgreich durchläuft, unterliegt zusätzlich
  einer konfigurierbaren Laufzeit-Sandbox, die von einer Location (analog zu `securityOverride`)
  oder global (analog zu `defaultSecurityChains`) festgelegt wird.
* Alle Laufzeit-Sandbox-Funktionen (API-Mediation, Thread-/Zeitlimit-Governance, Prozessisolation,
  Verstoßbehandlung) sind hinter einer einzigen Fassadenklasse **`PluginSandbox`** gebündelt -
  analog zu `PluginSecurity` als bestehender Anlaufstelle für die Sicherheitskette. `PluginManager`
  hält genau eine `PluginSandbox`-Instanz (`val sandbox: PluginSandbox`) und ruft ausschließlich
  deren Methoden auf; kein anderer Teil des Frameworks spricht Strategien, Executors oder den
  Java-Agent direkt an.
* Die Sandbox wirkt auf zwei Ebenen, die unabhängig zuschaltbar sind:
  1. **API-Mediation innerhalb der JVM**: direkter Zugriff auf risikobehaftete JDK-APIs
     (Dateisystem außerhalb eines erlaubten Wurzelverzeichnisses, Netzwerk, Prozessstart,
     Reflection auf Host-/Fremdplugin-Klassen, `System.exit`) wird über eine Bytecode-Instrumentierung
     (Java-Agent) unterbunden oder auf einen vom Host bereitgestellten, eingeschränkten
     Facade-API-Satz umgeleitet.
  2. **Ressourcen-/Thread-Governance**: von einem Plugin gestartete Threads laufen in einem
     eigenen, dem Plugin zugeordneten Thread-Pool/`ThreadGroup`; ein Watchdog erkennt
     Endlosausführung in Lifecycle-Hooks (`onEnable`/`onDisable`/`onUnload`) und
     Extension-Aufrufen und bricht sie nach einem konfigurierbaren Timeout ab bzw. meldet sie an
     die `ExceptionHandlingStrategy`.
* Für Plugins, die als besonders nicht vertrauenswürdig eingestuft werden (Location-Typ oder
  Manifest-Flag), steht optional eine **Prozessisolation** zur Verfügung: das Plugin läuft in
  einem separaten JVM-Subprozess und kommuniziert mit dem Host ausschließlich über ein
  **TLV-Protokoll nach ASN.1 DER**, kodiert/dekodiert über **Bouncy Castle**, auf einfachen
  `java.net`-Sockets (kein RMI, keine Java-Objektserialisierung) und kann dessen Prozessraum,
  Dateisystemzugriff und Ressourcen unabhängig vom Host-Prozess begrenzt bekommen (OS-Mittel:
  Working Directory, Umgebungsvariablen, ggf. Speicher-/CPU-Limits der gestarteten JVM). Eine
  OS-seitige Benutzertrennung für den Subprozess ist bewusst **nicht** Teil dieses Features (siehe
  Abschnitt 9).
* Der persistente Zustand des Frameworks (`PluginPersistenceStrategy`) ist gegen nachträgliche
  Manipulation durch einen HMAC-Integritätsschutz abgesichert: Ein bei Erst-Start der Anwendung
  zufällig erzeugter Schlüssel (nicht Teil der JAR, nicht hartkodiert) wird neben der
  Persistenzdatei abgelegt; jeder gespeicherte Wert wird beim Schreiben mit diesem Schlüssel
  signiert und beim Lesen verifiziert. Dies ist eine **Erschwerung/Erkennung** innerhalb der
  bestehenden Prozess-/Benutzergrenzen, keine Garantie gegen Code, der im selben Prozess und
  unter demselben OS-Benutzer läuft (siehe Abschnitt 9) - kombiniert mit der API-Mediation wird
  der Schlüssel für ein sandboxed In-VM-Plugin aber unerreichbar. Dieser Schutz ist bewusst kein
  Teil von `PluginSandbox`, da er unabhängig davon gilt, ob für ein Plugin überhaupt eine
  Sandbox-Policy konfiguriert ist (siehe Abschnitt 5).
* Die Sicherheitsprüfung eines Kandidaten (Checksum/Signatur) und dessen tatsächliches Laden
  verwenden **exakt dieselben, einmalig gelesenen Bytes** ("Byte-Pinning") statt zweier getrennter
  Dateizugriffe - die TOCTOU-Lücke aus Abschnitt 2 ist damit strukturell geschlossen, nicht nur
  zeitlich verkleinert. Der Vergleich des Checksum-Digests erfolgt zeitkonstant. Der
  ZIP/JAR-Eintrags-Parser, der für das Byte-Pinning die Klassen aus dem Speicher lädt, verwendet
  dieselbe Eintrags-Auflösungslogik wie die Signaturverifikation, sodass bei doppelten
  ZIP-Eintragsnamen keine Divergenz zwischen geprüftem und geladenem Eintrag entstehen kann.
  Zusätzlich prüft `SignatureSecurityStrategy` den Gültigkeitszeitraum (`notBefore`/`notAfter`) des
  verwendeten Zertifikats; ein abgelaufenes Zertifikat führt zu einem fehlgeschlagenen Check. Eine
  Widerrufsprüfung (CRL/OCSP) ist bewusst **nicht** Teil dieser Härtung (siehe Abschnitt 9).
* Sandbox-Verstöße führen zu einem definierten, protokollierten Zustand statt zu unbehandelten
  Exceptions oder stillem Fortbestehen: das betroffene Plugin wird sofort entladen, der Verstoß per
  WARN-Log und über `ExceptionHandlingStrategy` an den Host gemeldet.
* Ein kategorisierter API-Verstoß (Dateisystem/Netzwerk/Reflection/Prozessstart, über den
  Java-Agent erkannt) markiert den zugehörigen `PluginScanResult` zusätzlich mit dem eigenständigen
  Status `PluginScanStatus.POTENTIAL_ATTACK` - bewusst getrennt von `SECURITY_PROBLEM`, da Letzteres
  eine Vorab-Prüfung vor dem Laden betrifft, Ersteres ein bereits geladenes Plugin. Ein
  `POTENTIAL_ATTACK`-Plugin kann nicht per `PluginManager.forceLoad` erzwungen (wieder) geladen
  werden und kann einen `LOADED`-Kandidaten derselben Id nicht per Versions-Spoofing verdrängen
  (`IdCollisionResolver` behandelt es wie jeden anderen Nicht-`LOADED`-Status). Ein Zeitlimit-Verstoß
  durchläuft denselben Melde-/Entlade-Mechanismus, setzt aber erst nach mehreren aufeinanderfolgenden
  Timeouts für dasselbe Plugin (`PluginManager.MAX_TIMEOUT_VIOLATIONS`) denselben Zwangs-Unload-Pfad
  aus - ein einzelner Timeout allein gilt nicht als Angriffshinweis.
* Die bestehende Vorab-Sicherheitskette (`PluginSecurity`) bleibt unverändert; die Sandbox ist eine
  orthogonale, zusätzliche Schutzschicht, die erst nach erfolgreichem `PluginLoader.load()`
  greift.
* `IdCollisionResolver` lässt nur noch Kandidaten mit Status `LOADED` (d. h. die Sicherheitskette
  bereits erfolgreich durchlaufen haben) um die "beste" Version innerhalb einer Id-Kollisionsgruppe
  konkurrieren. Ein Kandidat mit einem anderen Status (z. B. `SECURITY_PROBLEM`) kann einen
  `LOADED`-Kandidaten derselben `id` nicht mehr per Versions-Spoofing verdrängen; sein eigener
  Status bleibt unverändert, statt fälschlich auf `ID_COLLISION` überschrieben zu werden.

## 4. Requirements

### Functional Requirements

* Ein Host kann pro `PluginLocation` (Override) oder global (Default) eine Sandbox-Konfiguration
  festlegen, analog zum bestehenden `securityOverride`/`defaultSecurityChain`-Muster.
* Ein Host kann einzelne risikobehaftete API-Kategorien (Dateisystem, Netzwerk, Reflection,
  Prozessstart, Thread-Erzeugung) pro Sandbox-Konfiguration einzeln erlauben/verbieten bzw. auf
  einen Facade-Ersatz umleiten.
* Ein Host kann pro Sandbox-Konfiguration ein Zeitlimit für Lifecycle-Hooks und
  Extension-Aufrufe festlegen; eine Überschreitung wird erkannt und behandelt.
* Ein Host kann ein Plugin als "prozessisoliert" markieren; ein solches Plugin läuft in einem
  eigenen JVM-Subprozess.
* Sämtliche Sandbox-Funktionalität ist über eine einzige Klasse `PluginSandbox` erreichbar; ein
  Host (und der Rest des Frameworks) muss nicht wissen, welche konkrete Strategie im Hintergrund
  zuständig ist.
* Sandbox-Verstöße werden über die bestehende `ExceptionHandlingStrategy` gemeldet und führen zur
  sofortigen Entladung des betroffenen Plugins (nicht des gesamten Hosts).
* Ein kategorisierter API-Verstoß (Dateisystem/Netzwerk/Reflection/Prozessstart) markiert den
  betroffenen Kandidaten als `PluginScanStatus.POTENTIAL_ATTACK`; ein so markierter Kandidat kann
  nicht per `PluginManager.forceLoad` erzwungen (wieder) geladen werden und kann keinen bereits
  geladenen Kandidaten derselben Id verdrängen.
* Bestehende Extension-Points und der Lifecycle (`PluginLifecycle`) funktionieren unverändert für
  Plugins ohne aktivierte Sandbox bzw. mit In-VM-Sandbox; für prozessisolierte Plugins wird die
  Extension-Aufruf-Semantik über die Bouncy-Castle-basierte ASN.1-DER/TLV-IPC-Schicht transparent
  nachgebildet, soweit technisch möglich.
* Jede `PluginPersistenceStrategy`-Implementierung (dateibasiert, Datenbank, benutzerdefiniert)
  kann wahlweise durch einen Integritätsschutz-Decorator umschlossen werden, ohne dass Framework
  oder Host zwischen geschützten und ungeschützten Keys unterscheiden müssen.
* Für jeden Kandidaten wird die Sicherheitsprüfung (Checksum/Signatur) auf denselben Bytes
  durchgeführt, die anschließend auch tatsächlich geladen werden - kein erneuter, ungeprüfter
  Dateizugriff zwischen Prüfung und Laden.
* Ein signierter Kandidat, dessen Zertifikat zum Prüfzeitpunkt abgelaufen ist, wird als
  Sicherheitsproblem erkannt, nicht stillschweigend akzeptiert.
* Ein Kandidat mit fehlgeschlagener Sicherheitsprüfung kann keinen bereits erfolgreich geprüften
  Kandidaten derselben Plugin-`id` durch eine höhere deklarierte Version verdrängen.

### Technical Requirements

* Keine Abhängigkeit von `java.lang.SecurityManager`/`AccessController` (JDK 25, JEP 486: entfernt).
* Reine Kotlin/Gradle-Umsetzung, keine neue Fremdabhängigkeit ohne Rückfrage beim Nutzer
  (siehe `dependencies.md`); für die Prozessisolation ist **Bouncy Castle** als
  ASN.1-Bibliothek vom Nutzer vorgegeben und damit als Fremdabhängigkeit bestätigt - eine
  eigene Serialisierungslösung entfällt dadurch für diesen Teil.
* `PluginSandbox` ist die einzige öffentliche API-Oberfläche der Laufzeit-Sandbox; die konkreten
  `PluginSandboxStrategy`-Implementierungen (Agent, Thread-Watchdog, Prozessisolation) sind
  intern und werden nicht direkt von `PluginManager` oder dem Host angesprochen - analog dazu, wie
  `PluginSecurityStrategy`-Implementierungen nur über `PluginSecurity` erreicht werden.
* Ein Java-Agent zur Bytecode-Instrumentierung benötigt einen vom Host gesetzten
  `-javaagent`-Start-Parameter (dynamisches Attachment wurde bewusst nicht verwendet); dies ist
  eine Host-seitige Voraussetzung.
* Die Bytecode-Instrumentierung nutzt die bereits bestehende Byte-Buddy-Abhängigkeit des Projekts -
  keine neue Fremdabhängigkeit.
* Die Prozessisolation ist ein eigenständiger, optionaler `PluginLoader`-Pfad; sie darf den
  bestehenden In-VM-Ladepfad nicht verändern oder verlangsamen, wenn sie nicht genutzt wird.
* Der Persistenz-Integritätsschutz nutzt ausschließlich JDK-Bordmittel
  (`javax.crypto.Mac`/`SecureRandom`), keine neue Fremdabhängigkeit; er verzichtet bewusst auf
  jede Form von OS-Prozess- oder Benutzertrennung, um die Nutzung des Plugin-Systems nicht zu
  verkomplizieren.
* Das Byte-Pinning und die Signatur-Härtung nutzen ausschließlich JDK-Bordmittel
  (`MessageDigest.isEqual` für den zeitkonstanten Vergleich, `ByteArray`-basiertes Klassenladen
  über `defineClass`, `X509Certificate.checkValidity()` für die Gültigkeitsprüfung), keine neue
  Fremdabhängigkeit; eine Widerrufsprüfung (CRL/OCSP), die echte PKI-Infrastruktur voraussetzen
  würde, ist bewusst nicht Teil davon.
* Sandbox-Konfiguration muss testbar sein, ohne echte bösartige Plugins zu benötigen (siehe
  `testing`-Skill vor Testklassen-Änderungen).

## 5. Architecture

* Fassadenklasse `PluginSandbox` in `org.pcsoft.framework.pluggiat.sandbox`, analog zu
  `PluginSecurity` - die zentrale Anlaufstelle für alles Sandbox-bezogene. Hält die konfigurierten
  `PluginSandboxStrategy`-Implementierungen intern und bündelt:
  * `activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult` -
    aktiviert Mediation/Thread-Governance für ein frisch geladenes Plugin; aufgerufen von
    `PluginManager` direkt nach `loader.load()` und vor der Extension-Aktivierung (analog zu
    `PluginSecurity.evaluate`).
  * `runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T` - führt einen
    Lifecycle-Hook- oder Extension-Aufruf unter der konfigurierten Thread-/Zeitlimit-Governance
    aus; zentrale Stelle statt verstreuter Executor-Handhabung in `PluginManager`/
    `ExtensionAggregator`.
  * `reportViolation(pluginId: String, violation: SandboxViolation)` - einheitliche
    Verstoßbehandlung, delegiert intern an `ExceptionHandlingStrategy` und
    `PluginPersistenceStrategy`.
  * `deactivate(pluginId: String)` - Aufräumen bei `unload`/`reload` (Executor-Shutdown,
    Freigabe agent-seitiger Zustände für dieses Plugin, soweit möglich).
  * `PluginSandboxPolicy` (Datenklasse/Konfiguration je Location bzw. global, analog zu
    `PluginSecurityStrategy`-Ketten): erlaubte API-Kategorien, Zeitlimits, Isolationsstufe.
  * `SandboxViolation`-Modell, angelehnt an `PluginSecurityCheckResult`.
* `PluginSandboxStrategy`-Schnittstelle mit austauschbaren, **ausschließlich intern von
  `PluginSandbox` verwendeten** Implementierungen (analog zur `PluginSecurityStrategy`-Kette, die
  ebenfalls nie direkt vom Host, sondern nur über `PluginSecurity` angesprochen wird):
  `AgentInstrumentationStrategy`, `ThreadWatchdog` (als eigenständiger Kollaborator),
  `ProcessIsolationStrategy`.
* Ein separates Modul/Package `org.pcsoft.framework.pluggiat.sandbox.agent` enthält den
  Java-Agent (`premain`/`agentmain`-Einstiegspunkt): Er registriert einen `ClassFileTransformer`
  über `java.lang.instrument.Instrumentation`, der beim Laden einer Plugin-Klasse riskante
  JDK-Aufrufe (`java.io.File`, `java.net.Socket`, `ProcessBuilder`, `System.exit`) durch
  Guard-Checks gegen die aktuelle `PluginSandboxPolicy` umschließt, sowie
  `Method.invoke`/`Class.forName` instrumentiert, um auch Reflection-basierte Umgehungen zur
  Laufzeit zu prüfen. `PluginClassLoader` selbst bleibt für die reine Klassensichtbarkeit
  zuständig (Plattform → Whitelist → eigene JARs → Dependencies) und wird durch den Agenten
  ergänzt, nicht ersetzt. Der Agent meldet erkannte Verstöße ausschließlich über
  `PluginSandbox.reportViolation`, nie direkt an `PluginManager`.
* Ein Package `org.pcsoft.framework.pluggiat.sandbox.process.der` (ursprünglich als `.ber`
  angelegt, nachträglich auf ASN.1 DER statt BER umgestellt - siehe unten) kapselt die Verwendung
  von **Bouncy Castle** für die IPC-Nachrichten: `DerCodec` bildet Extension-Aufrufe und
  Rückgabewerte auf Bouncy-Castle-ASN.1-Typen ab (`ASN1Integer`, `ASN1Boolean`, `DEROctetString`,
  `DERUTF8String`, `DERSequence`, `DERSet`) und versendet/empfängt sie über
  `ASN1OutputStream`/`ASN1InputStream` (explizit mit `ASN1Encoding.DER`) auf dem Socket; der
  Typumfang (`SandboxValue`) orientiert sich am tatsächlich benötigten Extension-Aufrufumfang,
  nicht an vollständiger ASN.1-Konformität. Ein komplexes Objekt (eine Kotlin-Data-Class, deren
  Felder rekursiv aus demselben Typumfang bestehen) wird zusätzlich als `SandboxValue.ObjectValue`
  unterstützt - kodiert als ASN.1 `SET` von `SEQUENCE { fieldName UTF8String, fieldValue Value }`
  -, automatisch gemappt über `SandboxTypeSupport` mittels `kotlin-reflect`.
* Ein als prozessisoliert konfiguriertes Plugin durchläuft weiterhin den regulären In-VM-Ladepfad
  (Manifest, Abhängigkeiten, Extension-Point-/Konfigurations-Mapping); lediglich die
  Instanziierung seiner Extension-Implementierungsklassen entfällt, der zugehörige Subprozess wird
  verzögert beim ersten tatsächlichen Extension-Aufruf gestartet. `ExtensionAggregator`/
  `ExtensionPointRegistry` behandeln den resultierenden IPC-Proxy transparent wie eine normale
  Extension-Implementierung; der `ProcessIsolationStrategy` innerhalb von `PluginSandbox` verwaltet
  den Subprozess und dessen Lebenszyklus.
* `PluginManager` bindet die Sandbox-Konfiguration analog zu `defaultSecurityChains` in
  `PluginManagerConfiguration` ein (`sandboxPolicies: Map<PluginLocationType, PluginSandboxPolicy>`,
  `PluginLocation.sandboxOverride`), hält `val sandbox: PluginSandbox` und ruft
  `sandbox.activate(...)` nach erfolgreichem `loader.load()` und vor der Aktivierung der
  Extensions auf; Lifecycle-Aufrufe und Extension-Methodenaufrufe laufen über
  `sandbox.runGoverned(...)` statt direkt im aufrufenden Thread des Hosts.
* Package `org.pcsoft.framework.pluggiat.persistence.integrity` mit
  `IntegrityProtectedPersistenceStrategy`: ein generischer Decorator um eine beliebige
  `PluginPersistenceStrategy`, **unabhängig von `PluginSandbox`** eingesetzt (er schützt auch dann,
  wenn für ein Plugin gar keine Sandbox-Policy konfiguriert ist). Beim ersten Zugriff wird ein
  256-Bit-Schlüssel per `SecureRandom` erzeugt und in einer separaten Schlüsseldatei neben der
  zugrunde liegenden Persistenzquelle abgelegt (bei `FilePersistenceStrategy` z. B.
  `<dateiname>.key` im selben Verzeichnis; bei nicht-dateibasierten Strategien über einen explizit
  konfigurierten Pfad), sofern noch keine existiert - andernfalls wird der vorhandene Schlüssel
  gelesen. Jeder `write(pluginId, key, value)`-Aufruf berechnet zusätzlich einen HMAC
  (`javax.crypto.Mac`, z. B. `HmacSHA256`) über `(pluginId, key, value)` und speichert ihn unter
  einem abgeleiteten Zusatzschlüssel in derselben zugrunde liegenden `PluginPersistenceStrategy`;
  `read(pluginId, key)` verifiziert den gespeicherten HMAC gegen den gelesenen Wert und liefert bei
  Mismatch `null` (fail-safe: als "nicht gesetzt" behandelt) statt des manipulierten Werts,
  inklusive Log-Meldung.
* Typ `PinnedPluginContent` in `org.pcsoft.framework.pluggiat.scanner`: kapselt die einmalig
  gelesenen Rohbytes eines Kandidaten - für `SingleJarScanStrategy`/`ZipJarScanStrategy` ein
  einzelnes `ByteArray`, für `MultiJarWithOwnFolderScanStrategy` eine nach Dateiname geordnete
  `Map<String, ByteArray>`. Die Bytes werden **einmal**, direkt im Rahmen von
  `PluginScanner.applySecurityCheck`, gelesen; `PluginSecurityStrategy.check()` hat eine
  Überladung, die `PinnedPluginContent` statt eines `Path` prüft (`ChecksumSecurityStrategy`/
  `SignatureSecurityStrategy` entsprechend angepasst). `PluginScanResult` trägt das
  `PinnedPluginContent` eines erfolgreich geprüften Kandidaten bis zum Laden weiter.
* Ein gemeinsames Hilfsmodul `org.pcsoft.framework.pluggiat.classloader.jar` stellt eine
  einzige Funktion zur Auflösung von ZIP/JAR-Einträgen aus `PinnedPluginContent` bereit
  (`resolveJarEntries(content): Map<String, ByteArray>`, deterministisch bei doppelten
  Eintragsnamen, "letzter Eintrag gewinnt" wie beim JDK-ZIP-Verhalten). Sowohl
  `SignatureSecurityStrategy` (Verifikation, für Manifest-JAR-/Checksum-Listen-Auffinden) als auch
  `PinnedPluginClassLoader` (Laden) verwenden diese eine Auflösungsfunktion, statt jeweils eigene
  ZIP-Parser-Instanzen mit potenziell abweichender Interpretation zu betreiben. Das schließt die
  in Abschnitt 2 beschriebene Duplicate-Entry-Divergenz strukturell, nicht nur durch Konvention.
* `SignatureSecurityStrategy` ruft zusätzlich zur bestehenden Schlüsselprüfung
  `certificate.checkValidity()` auf jedes verwendete Zertifikat auf; ein
  `CertificateExpiredException`/`CertificateNotYetValidException` wird als
  `PluginSecurityCheckResult.Failure` behandelt. Keine CRL-/OCSP-Anbindung, kein
  `CertPathValidator`/PKIX-Trust-Anchor-Aufbau - reine Prüfung des Gültigkeitszeitraums des bereits
  über `PublicKeyProviderStrategy` gepinnten Zertifikats.
* `PluginLoader.load()` hat eine Überladung, die ein `PinnedPluginContent` statt eines `Path`
  entgegennimmt; sie baut keinen `URLClassLoader` mehr auf, sondern einen speicherbasierten
  `PinnedPluginClassLoader`, der Klassen/Ressourcen ausschließlich aus den gepinnten Bytes
  auflöst (JAR-Einträge werden einmalig über die gemeinsame `resolveJarEntries`-Funktion
  indiziert, `findClass` nutzt `defineClass(name, bytes, off, len)`). Die gepinnten Bytes werden
  für die gesamte Lebensdauer des geladenen Plugins in `LoadedPlugin` gehalten, da Klassen lazy
  nachgeladen werden können. Der alte, `Path`-basierte Ladepfad bleibt für Aufrufer bestehen, die
  kein Pinning benötigen (z. B. ein Host-seitiger `InsecureSecurityStrategy`-Anwendungsfall ohne
  Integritätsanspruch), wird aber intern von `PluginManager` nicht mehr für frisch geprüfte
  Kandidaten verwendet.

**Nachträgliche Abweichung (nach Feature-Abschluss)**: Das IPC-Wireformat wurde von ASN.1 BER auf
ASN.1 DER umgestellt (`ASN1OutputStream.create(output, ASN1Encoding.DER)` statt des BER-Defaults)
und das Package `sandbox.process.ber`/die Klasse `BerCodec` in `sandbox.process.der`/`DerCodec`
umbenannt. Zusätzlich unterstützt die IPC seither komplexe Objekte (Kotlin-Data-Classes,
rekursiv aus dem bestehenden Typumfang) als `SandboxValue.ObjectValue`, automatisch gemappt über
`kotlin-reflect` (neue `implementation`-Abhängigkeit, vom Nutzer bestätigt). Dies engt den in
Abschnitt 6 beschriebenen "geschlossenen Typumfang" nicht ein, sondern erweitert ihn um einen
weiteren, ebenso geschlossenen Fall.

## 6. Risks and Open Questions

* **Kein `SecurityManager` verfügbar** (JDK 25 / JEP 486): jede In-VM-Durchsetzung basiert auf
  Bytecode-Instrumentierung und Konvention, nicht auf einem vom JDK garantierten
  Permission-Modell.
* **Java-Agent erfordert Host-Kooperation** (JDK 21+/JEP 451): ohne `-javaagent`-Start-Parameter
  kann `pluggiat` den Agenten nicht selbstständig aktivieren - dies ist eine
  Host-Integrationsanforderung; ein Host ohne gesetzten Parameter erhält beim Start eine Exception.
* **Restlücke trotz Agent**: Bytecode, der eine riskante JDK-Klasse referenziert, bevor der Agent
  registriert ist (sehr frühe Klasseninitialisierung), kann nicht rückwirkend erfasst werden. Nur
  der über `PluginClassLoader` geladene Code ist instrumentiert - eine über die SDK-Whitelist
  freigegebene Host-Methode, die eine riskante Operation im Auftrag des Plugins ausführt, bleibt
  unmediiert.
* **Kein hartes Thread-Abbrechen ohne Prozessgrenze**: `Thread.stop()` ist unsicher; der
  Thread-Watchdog kann Fehlverhalten erkennen (`callTimeout`, best-effort `Future.cancel(true)`),
  aber nur die Prozessisolation kann einen hängenden Aufruf garantiert beenden.
* **Bewusst keine OS-Prozess-/Benutzertrennung**: Der Nutzer hat entschieden, auf eine harte, vom
  Betriebssystem erzwungene Trennung (z. B. Windows Restricted Tokens/Integrity Levels als
  Äquivalent zu Unix-Benutzerwechsel) bewusst zu verzichten, um keine zusätzlichen
  Plattform-Abhängigkeiten (JNA/JNI für Windows) und Betriebskomplexität einzuführen. Konsequenz:
  Weder die Prozessisolation noch der Persistenz-Integritätsschutz bieten eine harte Garantie
  gegen Code, der im selben Prozess/unter demselben OS-Benutzer wie der Host läuft - beide sind
  Erschwerung/Erkennung, keine Garantie. Dies ist im MkDocs-Sicherheitskapitel explizit so benannt.
* **Speicherverbrauch und Funktionsumfang des Byte-Pinnings**: gepinnte Rohbytes bleiben für die
  gesamte Plugin-Lebensdauer im Speicher; native Bibliotheken und dateipfad-abhängige
  Ressourcenzugriffe aus Plugin-Code funktionieren mit einem rein speicherbasierten Classloader
  ggf. anders als zuvor - dokumentiert als bekannte Einschränkung in `sandbox.md`/`.de.md`.
* **Bewusst keine Widerrufsprüfung (CRL/OCSP)**: Eine echte Widerrufsprüfung würde eine betriebene
  PKI-Infrastruktur voraussetzen, die für selbstsignierte, per `PublicKeyProviderStrategy`
  gepinnte Zertifikate typischerweise nicht existiert. Ergänzt wird nur die
  Gültigkeitszeitraum-Prüfung (`notBefore`/`notAfter`). Ein kompromittierter, aber noch gültiger
  Schlüssel bleibt bis zu seinem regulären Ablauf vertrauenswürdig - eine bewusste, vom Nutzer
  akzeptierte Grenze.
* **Performance-Overhead**: Bytecode-Instrumentierung, dedizierte Executors, Bouncy-Castle-ASN.1-
  DER-IPC, HMAC-Berechnung pro Persistenzzugriff und vollständiges Einlesen der Kandidatenbytes
  vor dem Laden fügen Latenz/Speicherbedarf hinzu; ein Host mit performancekritischen
  Extension-Aufrufen kann die Sandbox gezielt abschalten.
* **Abwärtskompatibilität**: Standardverhalten ohne konfigurierte Sandbox-Policy entspricht dem
  Zustand ohne Sandbox (keine Einschränkung), analog zum expliziten Charakter von
  `InsecureSecurityStrategy` - eine Sandbox wird nicht implizit aktiv. Der
  Persistenz-Integritätsschutz ist ebenfalls ein Decorator, den ein Host explizit einsetzen muss.
  Ein bereits akzeptiertes, inzwischen abgelaufenes Signaturzertifikat führt zu einem neuen
  Fehlschlag, wo zuvor keiner war - eine gewollte Verhaltensänderung gegenüber dem in Abschnitt 2
  beschriebenen Ausgangszustand.

## 7. Feature Completion Criteria

* Ein Host kann für jede `PluginLocation` (oder global) eine Sandbox-Policy konfigurieren, die
  ohne explizite Konfiguration wirkungslos ist (kein impliziter Sicherheitsgewinn ohne
  Host-Entscheidung, analog zu `InsecureSecurityStrategy`).
* Sämtliche Laufzeit-Sandbox-Funktionalität ist ausschließlich über `PluginSandbox` erreichbar;
  `PluginManager` und Host sprechen keine konkrete Strategie direkt an.
* Ein In-VM-Plugin mit aktivierter, agent-basierter API-Mediation kann nachweislich nicht mehr
  uneingeschränkt auf mindestens Dateisystem, Netzwerk und Prozessstart zugreifen - weder direkt
  noch über Reflection -, sofern die Policy dies verbietet.
* Ein In-VM-Plugin, dessen Lifecycle-Hook das konfigurierte Zeitlimit überschreitet, blockiert den
  Host-Prozess nicht dauerhaft und wird als Sandbox-Verstoß erkannt.
* Ein als prozessisoliert konfiguriertes Plugin läuft nachweislich in einem eigenen Prozess, dessen
  Absturz den Host-Prozess nicht beendet, dessen Kommunikation ausschließlich über das
  Bouncy-Castle-basierte ASN.1-DER-TLV-Protokoll läuft (Werte inkl. komplexer Objekte als ASN.1
  `SET`/`SEQUENCE`), und dessen Extensions über die normale
  `getExtensions`/`getFirstExtension`-API weiterhin nutzbar sind.
* Eine direkte Manipulation der Persistenzdatei ohne Kenntnis des zugehörigen HMAC-Schlüssels wird
  beim nächsten `read` nachweislich erkannt und als "nicht gesetzt" behandelt, nicht stillschweigend
  übernommen.
* Ein Kandidat, dessen Datei(en) zwischen Sicherheitsprüfung und Laden ausgetauscht werden, wird
  nachweislich mit dem ursprünglich geprüften Inhalt geladen; der Checksum-Vergleich erfolgt
  zeitkonstant; ein JAR mit doppelten ZIP-Eintragsnamen wird von Verifikation und Laden identisch
  interpretiert; ein signierter Kandidat mit abgelaufenem Zertifikat wird abgelehnt.
* Ein Kandidat mit fehlgeschlagener Sicherheitsprüfung kann einen bereits erfolgreich geladenen
  Kandidaten derselben Plugin-`id` nachweislich nicht mehr durch eine höhere deklarierte Version
  aus der Kollisionsauflösung verdrängen.
* Sandbox-Verstöße (kategorisierter API-Verstoß wie auch wiederholte Zeitlimit-Überschreitung) sind
  über dieselbe Beobachtungs-/Persistenzschicht nachvollziehbar wie bestehende Sicherheits- und
  Deaktivierungsgründe.
* Die dokumentierten strukturellen Grenzen (kein `SecurityManager`, Host-Voraussetzung für den
  Java-Agent, kein hartes In-VM-Thread-Abbrechen, Bouncy-Castle-Abhängigkeit für die
  Prozessisolation, bewusster Verzicht auf OS-Prozess-/Benutzertrennung, Speicher-/
  Funktionsumfang-Tradeoffs des Byte-Pinnings sowie bewusster Verzicht auf Widerrufsprüfung) sind
  im MkDocs-Sicherheitskapitel als bewusste Design-Entscheidung festgehalten, nicht verschwiegen.
