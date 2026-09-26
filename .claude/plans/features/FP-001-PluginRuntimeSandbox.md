# Feature Plan: Plugin Runtime Sandbox

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
* Das JDK bietet keine öffentliche API zur ASN.1-BER-Kodierung/-Dekodierung (nur interne,
  projektintern nicht nutzbare `sun.security`-Klassen). Der Nutzer hat vorgegeben, für die
  BER-Kodierung/-Dekodierung die Fremdbibliothek **Bouncy Castle** (`org.bouncycastle:bcprov-jdk18on`,
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
  **TLV-Protokoll nach ASN.1 BER**, kodiert/dekodiert über **Bouncy Castle**, auf einfachen
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
  unter demselben OS-Benutzer läuft (siehe Abschnitt 9) - kombiniert mit der API-Mediation aus
  IP-02 wird der Schlüssel für ein sandboxed In-VM-Plugin aber unerreichbar. Dieser Schutz ist
  bewusst kein Teil von `PluginSandbox`, da er unabhängig davon gilt, ob für ein Plugin überhaupt
  eine Sandbox-Policy konfiguriert ist (siehe Abschnitt 5).
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
* Ein kategorisierter API-Verstoß (Dateisystem/Netzwerk/Reflection/Prozessstart, von IP-02 über den
  Java-Agent erkannt) markiert den zugehörigen `PluginScanResult` zusätzlich mit dem eigenständigen
  Status `PluginScanStatus.POTENTIAL_ATTACK` - bewusst getrennt von `SECURITY_PROBLEM`, da Letzteres
  eine Vorab-Prüfung vor dem Laden betrifft, Ersteres ein bereits geladenes Plugin. Ein
  `POTENTIAL_ATTACK`-Plugin kann nicht per `PluginManager.forceLoad` erzwungen (wieder) geladen
  werden und kann einen `LOADED`-Kandidaten derselben Id nicht per Versions-Spoofing verdrängen
  (`IdCollisionResolver` behandelt es wie jeden anderen Nicht-`LOADED`-Status). Ein Zeitlimit-Verstoß
  (IP-03) durchläuft denselben Melde-/Entlade-Mechanismus, setzt aber bewusst keinen
  `POTENTIAL_ATTACK`-Status, da ein Timeout allein kein Hinweis auf einen Angriff ist.
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
  Extension-Aufruf-Semantik über die Bouncy-Castle-basierte ASN.1-BER/TLV-IPC-Schicht transparent
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
  (siehe `dependencies.md`); für die Prozessisolation (IP-04) ist **Bouncy Castle** als
  ASN.1-BER-Bibliothek vom Nutzer vorgegeben und damit als Fremdabhängigkeit bestätigt - eine
  eigene Serialisierungslösung entfällt dadurch für diesen Teil.
* `PluginSandbox` ist die einzige öffentliche API-Oberfläche der Laufzeit-Sandbox; die konkreten
  `PluginSandboxStrategy`-Implementierungen (Agent, Thread-Watchdog, Prozessisolation) sind
  intern und werden nicht direkt von `PluginManager` oder dem Host angesprochen - analog dazu, wie
  `PluginSecurityStrategy`-Implementierungen nur über `PluginSecurity` erreicht werden.
* Ein Java-Agent zur Bytecode-Instrumentierung (IP-02) benötigt entweder einen vom Host gesetzten
  `-javaagent`-Start-Parameter oder ein bewusst freigeschaltetes dynamisches Attachment
  (`-XX:+EnableDynamicAgentLoading` ab JDK 21); dies ist mit dem Nutzer als Host-seitige
  Voraussetzung abzustimmen, bevor IP-02 begonnen wird.
* Die für die Bytecode-Instrumentierung benötigte Umschreibe-Bibliothek (z. B. ASM) ist eine neue
  Fremdabhängigkeit und daher vorab mit dem Nutzer abzustimmen (siehe `dependencies.md`) -
  unabhängig von der für IP-04 bereits bestätigten Bouncy-Castle-Abhängigkeit.
* Die Prozessisolation ist ein eigenständiger, optionaler `PluginLoader`-Pfad; sie darf den
  bestehenden In-VM-Ladepfad nicht verändern oder verlangsamen, wenn sie nicht genutzt wird.
* Der Persistenz-Integritätsschutz (IP-06) nutzt ausschließlich JDK-Bordmittel
  (`javax.crypto.Mac`/`SecureRandom`), keine neue Fremdabhängigkeit; er verzichtet bewusst auf
  jede Form von OS-Prozess- oder Benutzertrennung, um die Nutzung des Plugin-Systems nicht zu
  verkomplizieren.
* Das Byte-Pinning und die Signatur-Härtung (IP-07) nutzen ausschließlich JDK-Bordmittel
  (`MessageDigest.isEqual` für den zeitkonstanten Vergleich, `ByteArray`-basiertes Klassenladen
  über `defineClass`, `X509Certificate.checkValidity()` für die Gültigkeitsprüfung), keine neue
  Fremdabhängigkeit; eine Widerrufsprüfung (CRL/OCSP), die echte PKI-Infrastruktur voraussetzen
  würde, ist bewusst nicht Teil davon.
* Sandbox-Konfiguration muss testbar sein, ohne echte bösartige Plugins zu benötigen (siehe
  `testing`-Skill vor Testklassen-Änderungen).

## 5. Architecture

* Neue Fassadenklasse `PluginSandbox` in `org.pcsoft.framework.pluggiat.sandbox`, analog zu
  `PluginSecurity` - die zentrale Anlaufstelle für alles Sandbox-bezogene. Hält die konfigurierten
  `PluginSandboxStrategy`-Implementierungen intern und bündelt:
  * `activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult` -
    aktiviert Mediation/Thread-Governance für ein frisch geladenes Plugin; aufgerufen von
    `PluginManager` direkt nach `loader.load()` und vor der Extension-Aktivierung (analog zu
    `PluginSecurity.evaluate`).
  * `runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T` - führt einen
    Lifecycle-Hook- oder Extension-Aufruf unter der konfigurierten Thread-/Zeitlimit-Governance
    aus (IP-03); zentrale Stelle statt verstreuter Executor-Handhabung in `PluginManager`/
    `ExtensionAggregator`.
  * `reportViolation(pluginId: String, violation: SandboxViolation)` - einheitliche
    Verstoßbehandlung (IP-05), delegiert intern an `ExceptionHandlingStrategy` und
    `PluginPersistenceStrategy`.
  * `deactivate(pluginId: String)` - Aufräumen bei `unload`/`reload` (Executor-Shutdown,
    Freigabe agent-seitiger Zustände für dieses Plugin, soweit möglich).
  * `PluginSandboxPolicy` (Datenklasse/Konfiguration je Location bzw. global, analog zu
    `PluginSecurityStrategy`-Ketten): erlaubte API-Kategorien, Zeitlimits, Isolationsstufe.
  * `SandboxViolation`-Modell, angelehnt an `PluginSecurityCheckResult`.
* `PluginSandboxStrategy`-Schnittstelle mit austauschbaren, **ausschließlich intern von
  `PluginSandbox` verwendeten** Implementierungen (analog zur `PluginSecurityStrategy`-Kette, die
  ebenfalls nie direkt vom Host, sondern nur über `PluginSecurity` angesprochen wird): z. B.
  `AgentInstrumentationStrategy`, `ThreadWatchdogStrategy`, `ProcessIsolationStrategy`.
* Ein separates, neues Modul/Package `org.pcsoft.framework.pluggiat.sandbox.agent` enthält den
  Java-Agent (`premain`/`agentmain`-Einstiegspunkt): Er registriert einen `ClassFileTransformer`
  über `java.lang.instrument.Instrumentation`, der beim Laden einer Plugin-Klasse riskante
  JDK-Aufrufe (`java.io.File`, `java.net.Socket`, `ProcessBuilder`, `System.exit`) durch
  Guard-Checks gegen die aktuelle `PluginSandboxPolicy` umschließt, sowie
  `Method.invoke`/`Class.forName` instrumentiert, um auch Reflection-basierte Umgehungen zur
  Laufzeit zu prüfen. `PluginClassLoader` selbst bleibt für die reine Klassensichtbarkeit
  zuständig (Plattform → Whitelist → eigene JARs → Dependencies) und wird durch den Agenten
  ergänzt, nicht ersetzt. Der Agent meldet erkannte Verstöße ausschließlich über
  `PluginSandbox.reportViolation`, nie direkt an `PluginManager`.
* Ein neues Package `org.pcsoft.framework.pluggiat.sandbox.process.ber` kapselt die Verwendung von
  **Bouncy Castle** für die IPC-Nachrichten: Encoder/Decoder-Funktionen, die Extension-Aufrufe und
  Rückgabewerte auf Bouncy-Castle-ASN.1-Typen (z. B. `ASN1Integer`, `DERBoolean`, `DEROctetString`,
  `DERUTF8String`, `DERSequence`, `DERNull`) abbilden und über `ASN1OutputStream`/`ASN1InputStream`
  auf dem Socket versenden/empfangen; der Typumfang orientiert sich am tatsächlich benötigten
  Extension-Aufrufumfang, nicht an vollständiger ASN.1-Konformität.
* `PluginLoader.load()` bekommt einen zweiten Rückgabepfad für prozessisolierte Plugins: statt
  eines `PluginClassLoader` wird ein `LoadedPlugin`-Äquivalent erzeugt, das einen
  Bouncy-Castle-basierten IPC-Proxy-Handle auf den Subprozess kapselt; `ExtensionAggregator`/
  `ExtensionPointRegistry` müssen diesen Proxy-Fall transparent wie eine normale
  Extension-Implementierung behandeln können (dynamische Proxy-Klasse pro Extension-Interface, die
  Methodenaufrufe in ASN.1-BER-kodierte Nachrichten über den Socket überträgt); der
  `ProcessIsolationStrategy` innerhalb von `PluginSandbox` verwaltet diesen Subprozess und dessen
  Lebenszyklus.
* `PluginManager` bindet die Sandbox-Konfiguration analog zu `defaultSecurityChains` in
  `PluginManagerConfiguration` ein (`sandboxPolicies: Map<PluginLocationType, PluginSandboxPolicy>`,
  `PluginLocation.sandboxOverride`), hält `val sandbox: PluginSandbox` und ruft
  `sandbox.activate(...)` nach erfolgreichem `loader.load()` und vor der Aktivierung der
  Extensions auf; Lifecycle-Aufrufe und Extension-Methodenaufrufe laufen über
  `sandbox.runGoverned(...)` statt direkt im aufrufenden Thread des Hosts.
* Neues Package `org.pcsoft.framework.pluggiat.persistence.integrity` mit
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
* Neuer Typ `PinnedPluginContent` in `org.pcsoft.framework.pluggiat.scanner` (oder
  `org.pcsoft.framework.pluggiat.classloader`): kapselt die einmalig gelesenen Rohbytes eines
  Kandidaten - für `SingleJarScanStrategy`/`ZipJarScanStrategy` ein einzelnes `ByteArray`, für
  `MultiJarWithOwnFolderScanStrategy` eine nach Dateiname geordnete `Map<String, ByteArray>`. Die
  Bytes werden **einmal**, direkt im Rahmen von `PluginScanner.applySecurityCheck`, gelesen;
  `PluginSecurityStrategy.check()` bekommt eine neue Überladung, die `PinnedPluginContent` statt
  eines `Path` prüft (`ChecksumSecurityStrategy`/`SignatureSecurityStrategy` werden entsprechend
  angepasst). `PluginScanResult` trägt das `PinnedPluginContent` eines erfolgreich geprüften
  Kandidaten bis zum Laden weiter.
* Ein gemeinsames, neues Hilfsmodul `org.pcsoft.framework.pluggiat.classloader.jar` stellt eine
  einzige Funktion zur Auflösung von ZIP/JAR-Einträgen aus `PinnedPluginContent` bereit
  (`resolveJarEntries(content): Map<String, ByteArray>`, deterministisch bei doppelten
  Eintragsnamen, z. B. "letzter Eintrag gewinnt" wie beim JDK-ZIP-Verhalten). Sowohl
  `SignatureSecurityStrategy` (Verifikation) als auch `PinnedPluginClassLoader` (Laden) verwenden
  ausschließlich diese eine Auflösungsfunktion, statt jeweils eigene ZIP-Parser-Instanzen
  (`JarFile` bzw. eigener `JarInputStream`-Indexer) mit potenziell abweichender Interpretation zu
  betreiben. Das schließt die in Abschnitt 2 beschriebene Duplicate-Entry-Divergenz strukturell,
  nicht nur durch Konvention.
* `SignatureSecurityStrategy` ruft zusätzlich zur bestehenden Schlüsselprüfung
  `certificate.checkValidity()` (bzw. `checkValidity(Date)` bei Verwendung eines Prüfzeitpunkts)
  auf jedes verwendete Zertifikat auf; ein `CertificateExpiredException`/
  `CertificateNotYetValidException` wird als `PluginSecurityCheckResult.Failure` behandelt. Keine
  CRL-/OCSP-Anbindung, kein `CertPathValidator`/PKIX-Trust-Anchor-Aufbau - reine Prüfung des
  Gültigkeitszeitraums des bereits über `PublicKeyProviderStrategy` gepinnten Zertifikats.
* `PluginLoader.load()` bekommt eine neue Überladung, die ein `PinnedPluginContent` statt eines
  `Path` entgegennimmt; sie baut keinen `URLClassLoader` mehr auf, sondern einen neuen,
  speicherbasierten `PinnedPluginClassLoader`, der Klassen/Ressourcen ausschließlich aus den
  gepinnten Bytes auflöst (JAR-Einträge werden einmalig über die gemeinsame
  `resolveJarEntries`-Funktion indiziert, `findClass` nutzt `defineClass(name, bytes, off, len)`).
  Die gepinnten Bytes werden für die gesamte Lebensdauer des geladenen Plugins in `LoadedPlugin`
  gehalten, da Klassen lazy nachgeladen werden können. Der alte, `Path`-basierte Ladepfad bleibt
  für Aufrufer bestehen, die kein Pinning benötigen (z. B. ein Host-seitiger
  `InsecureSecurityStrategy`-Anwendungsfall ohne Integritätsanspruch), wird aber intern von
  `PluginManager` nicht mehr für frisch geprüfte Kandidaten verwendet.

## 6. Implementation Plan Overview

| ID    | Implementation Plan                         | Objective                                                                 | Dependencies |
| ----- | -------------------------------------------- | -------------------------------------------------------------------------- | ------------ |
| IP-01 (COMPLETED) | Sandbox-Grundmodell, `PluginSandbox`-Fassade und Konfiguration | Policy-/Strategie-Abstraktionen, `PluginSandbox` als Anlaufstelle, Einbindung in `PluginManagerConfiguration` | -            |
| IP-02 (COMPLETED) | Agent-basierte Bytecode-API-Mediation        | Zugriffskontrolle auf riskante JDK-APIs via Java-Agent/Instrumentierung, angebunden über `PluginSandbox`; bei Verstoß Sofort-Entladung und `POTENTIAL_ATTACK`-Status | IP-01        |
| IP-03 | Thread- und Zeitlimit-Governance             | Dedizierte Executors, Watchdog für Lifecycle-/Extension-Aufrufe, angebunden über `PluginSandbox`            | IP-01        |
| IP-04 | Prozessisolation für hochriskante Plugins    | Subprozess-basierte Isolation mit Bouncy-Castle-ASN.1-BER-IPC-Proxy, verwaltet über `PluginSandbox`         | IP-01        |
| IP-05 | Verstoßbehandlung und Beobachtbarkeit        | Zeitlimit-Verstöße (IP-03) an die bereits von IP-02 real implementierte `PluginSandbox.reportViolation`-Logik anschließen | IP-02, IP-03 |
| IP-06 (COMPLETED) | Persistenz-Integritätsschutz                 | HMAC-Schutz gegen selbstbegünstigende Manipulation des persistenten Zustands | -          |
| IP-07 (COMPLETED) | Checksum-/Signatur-Härtung (Byte-Pinning)    | TOCTOU-Lücke schließen, konsistente Zip-Interpretation, zeitkonstanter Digest-Vergleich, Zertifikats-Gültigkeitsprüfung | - |
| IP-08 (COMPLETED) | Kollisionsauflösung nach Sicherheitsstatus filtern | Verhindern, dass ein sicherheitsgeprüft fehlgeschlagener Kandidat einen erfolgreich geladenen per Versions-Spoofing verdrängt | - |

## 7. Implementation Plans

### IP-01: Sandbox-Grundmodell, `PluginSandbox`-Fassade und Konfiguration (COMPLETED)

**Objective**

Die grundlegenden Abstraktionen für eine Sandbox-Richtlinie sowie die zentrale Fassadenklasse
`PluginSandbox` schaffen und sie analog zum bestehenden Sicherheitsketten-Muster in
`PluginManagerConfiguration`/`PluginLocation` einhängen, ohne bereits eine konkrete Durchsetzung
zu implementieren.

**Scope**

Enthalten: `PluginSandbox` als öffentliche Fassadenklasse mit den (in diesem Schritt als No-Op
implementierten) Methoden `activate`, `runGoverned`, `reportViolation`, `deactivate`;
`PluginSandboxPolicy`, `PluginSandboxStrategy`-Interface, `SandboxCheckResult`/`SandboxViolation`
(analog `PluginSecurityCheckResult`), Konfigurationspunkte in `PluginManagerConfiguration`
(`sandboxPolicies`) und `PluginLocationBuilder`/`PluginLocation` (`sandboxOverride`), ein
No-Op-Strategie als Default. Nicht enthalten: jegliche echte Durchsetzungslogik (das ist
IP-02/03/04); `PluginManager` ruft `sandbox.activate`/`runGoverned` bereits auf, jedoch noch ohne
Wirkung.

**Affected Areas**

`PluginManagerConfiguration`, `PluginLocationBuilder`, `PluginLocation`, `PluginManager` (neues
`val sandbox: PluginSandbox` sowie die Aufrufstellen), neues Package
`org.pcsoft.framework.pluggiat.sandbox`.

**Dependencies**

Keine.

**Expected Result**

Ein Host kann eine (zunächst wirkungslose) Sandbox-Konfiguration pro Location/global setzen;
`PluginManager` spricht ausschließlich `PluginSandbox` an; die Struktur ist bereit, damit
IP-02/IP-03/IP-04 konkrete Strategien *innerhalb* von `PluginSandbox` andocken können, ohne dass sich
die Aufrufstellen in `PluginManager` nochmals ändern.

**Technical Considerations**

Muster von `SecurityChainBuilder`/`DefaultSecurityChainBuilder` sowie das Verhältnis
`PluginManager.security: PluginSecurity` wiederverwenden, um Konsistenz mit der bestehenden DSL
und Architektur zu wahren. Keine Bruch-Änderung an bestehenden Signaturen von `PluginManager`,
`PluginLoader.load()` etc. in diesem Schritt - `PluginSandbox` wird rein additiv eingehängt.

**Tatsächliche Umsetzung**

Wie geplant umgesetzt, mit einer Präzisierung: `sandbox.activate(...)` wird nach jedem
`loader.load()`-Aufruf in `scan`, `reactivate` UND `forceLoad` aufgerufen (nicht nur in `scan`, wie
in einer früheren Planfassung angenommen) - `reactivate`/`forceLoad` sind seit IP-06/IP-07/IP-08
weitere Lade-Einstiegspunkte neben `scan`. `sandbox.runGoverned` umschließt aktuell nur die direkten
`PluginLifecycle`-Aufrufe in `PluginManager.unload()`, da dies die einzige Stelle ist, an der
`PluginManager` selbst Lifecycle-Hooks aufruft; die Anbindung der `onLoad`/`onEnable`-Aufrufe in
`ExtensionAggregator` bleibt Aufgabe von IP-03.

### IP-02: Agent-basierte Bytecode-API-Mediation (COMPLETED)

**Objective**

Direkten Zugriff eines Plugins auf riskante JDK-APIs (Dateisystem außerhalb eines erlaubten
Wurzelverzeichnisses, Netzwerk, Reflection auf Host-/Fremdplugin-interne Klassen, Prozessstart,
`System.exit`) über einen Java-Agent zur Ladezeit unterbinden oder auf Host-kontrollierte
Facade-Klassen umleiten, angebunden über `PluginSandbox.activate`.

**Scope**

Enthalten: Java-Agent-Modul mit `premain`/`agentmain`-Einstiegspunkt, ein
`ClassFileTransformer` (umgesetzt über die bereits im Projekt vorhandene Byte-Buddy-Abhängigkeit),
der Plugin-Bytecode beim Laden umschreibt und vor riskanten Aufrufen Guard-Checks gegen die aktive
`PluginSandboxPolicy` einfügt, zusätzliche Instrumentierung von
`Method.invoke`/`Class.forName`/`MethodHandles.Lookup`, um Reflection-basierte Umgehungen zur
Laufzeit abzufangen; `AgentInstrumentationStrategy` als interne, von `PluginSandbox.activate`
aufgerufene Implementierung von `PluginSandboxStrategy`; Konfigurationsmodell für erlaubte
API-Kategorien in `PluginSandboxPolicy`; verpflichtende Prüfung beim Start, ob der Java-Agent aktiv
ist, mit Abbruch der Anwendung per Exception falls nicht; **echte Implementierung von
`PluginSandbox.reportViolation`** für kategorisierte API-Verstöße (statt No-Op): Sofort-Entladung
des betroffenen Plugins, neuer Status `PluginScanStatus.POTENTIAL_ATTACK`, WARN-Log mit explizitem
Sicherheitsrisiko-Hinweis, Weiterleitung an `ExceptionHandlingStrategy`, Sperrung von
`PluginManager.forceLoad` für `POTENTIAL_ATTACK`-Kandidaten; Dokumentation der Host-seitigen
Voraussetzung (`-javaagent`-Start). Nicht enthalten: Thread-/Zeitlimits (IP-03), Prozessisolation
(IP-04); dynamisches Attachment als Alternative zu `-javaagent` wurde bewusst verworfen.

**Affected Areas**

Neues Agent-Modul/Package `org.pcsoft.framework.pluggiat.sandbox.agent`, `PluginSandbox`
(Einhängen des `AgentInstrumentationStrategy`, echte `reportViolation`-Implementierung),
`PluginScanResult`/`PluginScanStatus` (neuer Status `POTENTIAL_ATTACK`), `PluginManager`
(Start-Verifikation, `forceLoad`-Sperre), `PluginLoader`.

**Dependencies**

IP-01.

**Expected Result**

Ein Plugin, dessen Sandbox-Policy z. B. Netzwerkzugriff verbietet, kann `java.net.Socket` & Co.
weder direkt noch über Reflection mehr nutzen: der Zugriff wird abgefangen, das Plugin sofort
entladen, als `POTENTIAL_ATTACK` markiert und der Verstoß protokolliert sowie an den Host gemeldet.
Ein so markiertes Plugin lässt sich nicht per `forceLoad` erneut laden und kann einen bereits
geladenen Kandidaten derselben Id nicht verdrängen. Ein Host ohne gesetzten `-javaagent`-Parameter
erhält beim Start eine Exception statt eines unbemerkt wirkungslosen Schutzes.

**Technical Considerations**

Kein `SecurityManager` verfügbar (JDK 25) - die Durchsetzung erfolgt stattdessen über
Bytecode-Instrumentierung zur Ladezeit, was auch dynamisch aufgelöste Reflection-Aufrufe abdecken
kann (im Gegensatz zu einem rein statischen Vorab-Scan). Ein Java-Agent ist auf JDK 25 kein rein
bibliotheksseitiger Mechanismus mehr: er benötigt einen vom Host gesetzten `-javaagent`-Parameter
beim JVM-Start - diese Host-Voraussetzung wird beim Start aktiv verifiziert und bei Fehlen mit einer
Exception durchgesetzt, statt sie nur zu dokumentieren. Die Umschreibe-Bibliothek für den
`ClassFileTransformer` ist Byte Buddy (`net.bytebuddy:byte-buddy`), bereits bestehende Abhängigkeit
des Projekts - keine neue Fremdabhängigkeit nötig. Auch mit Agent bleibt eine Restlücke: Bytecode,
der eine riskante JDK-Klasse referenziert, bevor der Agent registriert ist (z. B. bei sehr früher
Klasseninitialisierung), kann nicht rückwirkend erfasst werden - diese Grenze ist in Abschnitt 9 zu
dokumentieren. `PluginScanStatus.POTENTIAL_ATTACK` ist bewusst von `SECURITY_PROBLEM` getrennt: Ein
generischer, nicht-`LOADED`-Statusfilter in `IdCollisionResolver` schließt ihn automatisch von
Versions-Spoofing aus, ohne eigene Codeänderung dort. Diese Instrumentierung ist außerdem die
Grundlage dafür, dass der in IP-06 neu eingeführte HMAC-Schlüssel für ein sandboxed Plugin
tatsächlich unerreichbar wird (Zugriff auf die Schlüsseldatei fällt unter dieselbe
Dateisystem-Policy).

**Tatsächliche Umsetzung**

Wie geplant umgesetzt (Byte Buddy statt ASM, ausschließlich `-javaagent`, mit sofortiger
Verstoßbehandlung und `POTENTIAL_ATTACK` bereits in diesem Plan statt erst in IP-05, siehe dessen
eigene Abweichungsnotiz), mit einer Präzisierung: Für die Tests zu Dateisystem-/Netzwerk-/
Reflection-Blockierung gibt es keinen echten Ende-zu-Ende-Test mit tatsächlich per `-javaagent`
gestarteter Test-JVM (der Gradle-`test`-Task startet ohne diesen Parameter) - stattdessen
Unit-Tests auf den beiden Ebenen, aus denen sich die Blockierung zusammensetzt: die
Aufrufstellen-zu-Kategorie-Zuordnung (`guardedCategoryFor`) und die eigentliche Registry-Blockierung
samt Verstoßmeldung (`SandboxGuardRegistry.check`). Zusätzlich zur geplanten Dokumentation wurde auf
Nutzerwunsch ein MkDocs-Schnellstart sowie ein "Security recommendations"-Abschnitt auf jeder
Host-Integration-Seite mit einem sicherheitsrelevanten Stellhebel ergänzt. Über den ursprünglichen
Plan hinaus deckt `guardedCategoryFor` zusätzlich `java.nio`-basierte Zugriffspfade ab, die
`java.io.File`/`java.net.Socket` sonst vollständig umgehen könnten (Nutzer-Hinweise nach Abschluss von
IP-02 - zwei Nachbesserungsrunden, ausgelöst durch die Fragen nach `java.nio` und `nio.Path`):

* **Abdeckung `java.nio` und weitere Umgehungspfade**: `FILESYSTEM` deckt jetzt zusätzlich
  `java.nio.file.Files`/`Paths`/`FileSystem`/`FileSystems`/`DirectoryStream`/`WatchService`, die SPI
  `java.nio.file.spi.FileSystemProvider` (erreichbar über `path.getFileSystem().provider()`),
  `FileChannel`/`AsynchronousFileChannel`, die dateisystemberührenden `Path`-Member
  (`toRealPath`/`register`/`toFile`), `FileReader`/`FileWriter`/`FileDescriptor`, dateiöffnende
  Konstruktoren von `PrintStream`/`PrintWriter`/`Scanner`/`Formatter` (deskriptorabhängig) sowie
  `ZipFile`/`JarFile`/`ImageIO`/`FileHandler` ab. `NETWORK` zusätzlich die
  `java.nio.channels`-Netzwerkkanäle und `java.nio.channels.spi`, `javax.net`(`.ssl`)-Socket-Factories,
  `URLConnection`-Hierarchie, `InetAddress` (DNS), `NetworkInterface`, `MulticastSocket`,
  `java.net.http.HttpClient`, `java.rmi`, `javax.naming` (JNDI). `PROCESS_START` zusätzlich
  `Runtime.exec`/`halt`/`addShutdownHook`, `Process`/`ProcessHandle` und das Laden nativen Codes.
  `REFLECTION` jetzt paketweit `java.lang.reflect`/`java.lang.invoke` (statt nur `Method.invoke` und
  einzelner `Lookup`-Methoden - `Field.setAccessible`, `Constructor.newInstance`, `MethodHandle.invoke`,
  `VarHandle`, `privateLookupIn` waren offen), dazu `Unsafe`, `ObjectInputStream` und
  `ClassLoader.defineClass`/`loadClass`.
* **`THREAD_CREATION` wird überhaupt erst jetzt durchgesetzt** - die Kategorie war in IP-01 deklariert,
  aber von keinem Guard ausgewertet (`Thread`-Erzeugung/-Start, `ThreadGroup`, `Executors`, Pool-/
  `Timer`-Konstruktion, asynchrone `CompletableFuture`-Stufen).
* **Zwei strukturelle Umgehungen geschlossen**: (1) `java.io.File`/`Socket`/`ServerSocket`/
  `DatagramSocket` werden bei **jedem** Methodenaufruf bewacht statt nur am Konstruktor, da das JDK
  selbst Instanzen liefert (`Path.toFile()`, `SocketChannel.socket()`); (2) neue
  `guardedCategoryForSubtype`-Auflösung über die Typhierarchie (Byte-Buddy-`TypePool`), da der Owner im
  Bytecode der *statische* Typ der Aufrufstelle ist - `class MyFile : File` bzw. eine
  Fremdbibliotheks-`Socket`-Subklasse wären sonst vollständig unbewacht geblieben. Die Auflösung wendet
  die Member-Kuratierung des Basistyps an und wird bewusst **nicht** prozessweit gecacht (ein
  `owner`-String ist nur pro Classloader eindeutig; ein globaler Cache wäre ein False-Negative-Risiko).
* **Methodenreferenzen** (`Files::readAllBytes`) erzeugen keine `INVOKE*`-Instruktion im Plugin-Bytecode
  und werden daher zusätzlich in `visitInvokeDynamicInsn` bewacht - dort ausschließlich über die
  Bootstrap-*Argumente*, nie über das Bootstrap-Handle selbst (das ist bei jedem Lambda und jeder
  String-Konkatenation `LambdaMetafactory`/`StringConcatFactory` und würde normalen Code als
  Reflection-Angriff melden).
* **Bewusst gegen paketweites Matching für `java/io`, `java/nio` und `java/net`** entschieden: dort
  liegen überwiegend harmlose In-Memory-Typen (`ByteArrayOutputStream`, `ByteBuffer`, `URI`,
  `PrintStream.println` → `System.out.println`), deren Blockade legitime Plugins zerstören würde - und
  da ein Fehlalarm über `POTENTIAL_ATTACK` irreversibel ist (kein `forceLoad`), ist ein False Positive
  hier schädlicher als in einer gewöhnlichen Zugriffskontrolle. Paketweit abgesichert wird nur, wo es
  keinen harmlosen Member gibt (`java/lang/reflect/`, `java/lang/invoke/`, `sun/misc/`,
  `jdk/internal/misc/`, `java/nio/file/spi/`, `java/nio/channels/spi/`, `javax/net/`, `java/rmi/`,
  `javax/naming/`).
* **Verbleibende, dokumentierte Grenze**: nur der über `PluginClassLoader` geladene Code ist
  instrumentiert - eine über die SDK-Whitelist freigegebene Host-Methode, die eine riskante Operation
  im Auftrag des Plugins ausführt, bleibt unmediiert (in `sandbox.md`/`.de.md` als Einschränkung
  dokumentiert, inkl. Empfehlung zur Whitelist-Gestaltung).

### IP-03: Thread- und Zeitlimit-Governance

**Objective**

Von einem Plugin verursachte Endlosausführung oder unkontrollierte Thread-Erzeugung in
Lifecycle-Hooks und Extension-Aufrufen begrenzen, angebunden über `PluginSandbox.runGoverned`.

**Scope**

Enthalten: `ThreadWatchdogStrategy` als interne `PluginSandboxStrategy`-Implementierung mit
dediziertem Executor/ThreadGroup pro geladenem Plugin, aufgerufen über
`PluginSandbox.runGoverned(pluginId, policy) { ... }` für `PluginLifecycle.onEnable/onDisable/
onUnload`-Aufrufe und Extension-Methodenaufrufe (soweit über den Aggregator/Proxy-Mechanismus
abgefangen werden kann), Watchdog-Logik. Nicht enthalten: API-Mediation (IP-02), Prozessisolation
(IP-04).

**Affected Areas**

`PluginSandbox` (Einhängen des `ThreadWatchdogStrategy`, Implementierung von `runGoverned`),
`PluginManager` (Lifecycle-Aufrufe in `unload`/`reload`/`scan` rufen `sandbox.runGoverned` statt
direkt auf), `ExtensionAggregator`.

**Dependencies**

IP-01.

**Expected Result**

Ein Plugin, das in `onEnable` blockiert oder eine Endlosschleife startet, blockiert den Host nicht
mehr unbegrenzt; nach Ablauf des konfigurierten Zeitlimits wird der Aufruf abgebrochen und an
`PluginSandbox.reportViolation` übergeben. Da `reportViolation` bis zur Umsetzung von IP-05 ein
No-Op ist (siehe IP-01), ist das für sich allein beobachtbare Ergebnis dieses Plans: der Host wird
zuverlässig nicht mehr blockiert; eine protokollierte oder auf das Plugin reagierende Meldung ist
erst nach IP-05 sichtbar.

**Technical Considerations**

Echtes Abbrechen eines laufenden JVM-Threads ist ohne kooperatives Verhalten des Plugin-Codes nur
eingeschränkt möglich (`Thread.stop()` ist unsicher und seit Langem als gefährlich markiert); die
Governance kann Timeouts erkennen und den Thread als "verwaist" markieren/isolieren, aber ein
garantiertes hartes Abbrechen ist nur über Prozessisolation (IP-04) erreichbar - diese Grenze ist
in Abschnitt 9 zu dokumentieren.

### IP-04: Prozessisolation für hochriskante Plugins

**Objective**

Für Plugins, die als nicht vertrauenswürdig genug für In-VM-Ausführung eingestuft werden, eine
Ausführung in einem separaten JVM-Subprozess mit einer über Bouncy Castle (ASN.1 BER) kodierten
Socket-Schnittstelle zum Host anbieten, verwaltet über `PluginSandbox`.

**Scope**

Enthalten: `ProcessIsolationStrategy` als interne `PluginSandboxStrategy`-Implementierung,
Subprozess-Start und -Lebenszyklus-Management, ein IPC-Modul
(`org.pcsoft.framework.pluggiat.sandbox.process.ber`), das Extension-Aufrufe und Rückgabewerte über
**Bouncy Castle** (`org.bouncycastle:bcprov-jdk18on`) auf ASN.1-BER-TLV-Nachrichten abbildet und
über `java.net.ServerSocket`/`Socket` überträgt (kein RMI, keine Java-Objektserialisierung),
Fehler-/Timeout-Behandlung über die Prozessgrenze hinweg, dynamischer Proxy pro
Extension-Interface, der `ExtensionAggregator`/`ExtensionPointRegistry` transparent bedient. Nicht
enthalten: In-VM-Mediation (IP-02), Thread-Governance innerhalb des Hostprozesses (IP-03, gilt dort
weiterhin für In-VM-Plugins), jegliche OS-Benutzer-/Rechtetrennung des Subprozesses (bewusst
ausgeschlossen, siehe Abschnitt 9).

**Affected Areas**

`PluginSandbox` (Einhängen des `ProcessIsolationStrategy`), `PluginLoader`,
`LoadedPlugin`/`PluginLoadResult`, `ExtensionAggregator`, `ExtensionPointRegistry`, neues Package
`org.pcsoft.framework.pluggiat.sandbox.process` inkl. Unterpackage
`org.pcsoft.framework.pluggiat.sandbox.process.ber`, `build.gradle.kts`
(Bouncy-Castle-Abhängigkeit).

**Dependencies**

IP-01.

**Expected Result**

Ein als prozessisoliert markiertes Plugin läuft in einem eigenen JVM-Prozess; seine Extensions sind
für den Host über `getExtensions`/`getFirstExtension` weiterhin nutzbar, Aufrufe werden
transparent als über Bouncy Castle kodierte ASN.1-BER-TLV-Nachrichten über den Socket
weitergereicht; ein Absturz oder Timeout des Subprozesses beeinträchtigt den Host-Prozess nicht.

**Technical Considerations**

Höchster Implementierungsaufwand der fünf Pläne; nicht jede Extension-Schnittstelle ist ohne
Weiteres auf die verwendeten Bouncy-Castle-ASN.1-Typen abbildbar (komplexe, nicht trivial
kodierbare Parameter/Rückgabewerte, zustandsbehaftete Objekte). Der Umfang der unterstützten
Extension-Signaturen und der verwendeten ASN.1-Typen (`ASN1Integer`, `DERBoolean`,
`DEROctetString`, `DERUTF8String`, `DERSequence`, `DERNull` als Startumfang) muss im Detailplan
explizit eingegrenzt werden. Da Bouncy Castle als geprüfte, weit verbreitete Bibliothek die
BER-Kodierung/-Dekodierung übernimmt, entfällt das Risiko eines selbst geschriebenen,
fehleranfälligen Parsers; dennoch bleibt zu klären, welche Bouncy-Castle-Modulteile (reines ASN.1
aus `bcprov` reicht voraussichtlich aus, kein TLS/Crypto-Funktionsumfang nötig) tatsächlich benötigt
werden, um die Abhängigkeit minimal zu halten. Der Subprozess läuft bewusst unter demselben
OS-Benutzer wie der Host, ohne zusätzliche Rechtetrennung (Nutzerentscheidung, siehe Abschnitt 9).
OS-seitige Ressourcenlimits (Speicher, CPU) für den Subprozess sind plattformabhängig und ggf. nur
eingeschränkt umsetzbar - dies ist als offene Frage in Abschnitt 9 zu klären, bevor der Detailplan
geschrieben wird.

### IP-05: Verstoßbehandlung und Beobachtbarkeit

**Objective**

Zeitlimit-Verstöße aus IP-03 an die bereits von IP-02 real implementierte
`PluginSandbox.reportViolation`-Logik anschließen, sodass beide Verstoßarten (kategorisierter
API-Verstoß und Zeitlimit-Verstoß) einheitlich gemeldet, protokolliert und in einen definierten
Plugin-Zustand überführt werden.

**Scope**

Enthalten: Anbindung von Zeitlimit-Verstößen (ohne zugeordnete `SandboxApiCategory`, siehe IP-03)
an das von IP-02 bereits real implementierte `reportViolation` (Sofort-Entladung, WARN-Log,
`ExceptionHandlingStrategy`-Weiterleitung greifen dadurch unverändert); neue Konstante
`SANDBOX_TIMEOUT_REASON` für die Persistenz des Deaktivierungsgrunds analog zum
`DISABLED_REASON_PERSISTENCE_KEY`-Muster aus `ExtensionAggregator`; explizite Abgrenzung, dass ein
reiner Zeitlimit-Verstoß **nicht** den Status `PluginScanStatus.POTENTIAL_ATTACK` setzt (dieser
bleibt kategorisierten API-Verstößen aus IP-02 vorbehalten, siehe Abschnitt 3). Nicht enthalten:
erneute Implementierung von `reportViolation` selbst (bereits Teil von IP-02); Verstöße aus der
Prozessisolation (IP-04), die aufgrund der abweichenden Fehlermodi (Prozessabsturz statt
In-VM-Exception) gesondert zu behandeln sind und hier bewusst ausgeklammert bleiben.

**Affected Areas**

`PluginSandbox` (Fallunterscheidung nach `SandboxViolation.category` in der von IP-02
implementierten `reportViolation`), `ExtensionAggregator` (neue Konstante).

**Dependencies**

IP-02, IP-03.

**Expected Result**

Ein Zeitlimit-Verstoß eines In-VM-Plugins führt zu demselben nachvollziehbaren, protokollierten
Zustand wie ein kategorisierter API-Verstoß (Sofort-Entladung, Persistenz, Host-Meldung), aber ohne
`POTENTIAL_ATTACK`-Markierung und ohne Sperrung von `forceLoad` - ein Timeout allein gilt nicht als
Angriffshinweis.

**Technical Considerations**

Konsistenz mit dem bestehenden `DISABLED_REASON_PERSISTENCE_KEY`/`ENABLED_PERSISTENCE_KEY`-Muster
aus `ExtensionAggregator` wahren, damit ein Host Sicherheits- und Sandbox-bedingte Deaktivierungen
über denselben Mechanismus auswerten kann. Die Unterscheidung API-Verstoß vs. Zeitlimit-Verstoß
erfolgt einzig über `SandboxViolation.category` (`null` bei Zeitlimit) - keine zweite
`reportViolation`-Implementierung, keine Code-Duplikation zwischen IP-02 und IP-05.

### IP-06: Persistenz-Integritätsschutz (COMPLETED)

**Objective**

Verhindern, dass ein Plugin (oder ein Dritter mit Dateisystemzugriff) den über
`PluginPersistenceStrategy` gespeicherten Zustand unbemerkt zu seinen eigenen Gunsten verändert
(z. B. `checksum`, `securityException`, `enabled`), ohne die Nutzung des Plugin-Systems durch
zusätzliche OS-Mechanismen zu verkomplizieren.

**Scope**

Enthalten: `IntegrityProtectedPersistenceStrategy` als generischer Decorator um eine beliebige
bestehende `PluginPersistenceStrategy`-Implementierung; Schlüsselerzeugung per `SecureRandom` bei
Erst-Start (nicht in der JAR, nicht hartkodiert), Ablage in einer separaten Schlüsseldatei neben
der zugrunde liegenden Persistenzquelle; HMAC-Berechnung (`javax.crypto.Mac`) pro `write`,
Verifikation pro `read` mit Fail-Safe-Verhalten (Mismatch → Wert gilt als nicht gesetzt, Log-Eintrag
statt stillem Fortsetzen). Nicht enthalten: jegliche OS-Prozess-/Benutzertrennung (siehe Abschnitt
9), Unterscheidung zwischen "sicherheitskritischen" und "normalen" Keys - der Schutz gilt
einheitlich für den gesamten Store, um die Konfiguration einfach zu halten; keine Anbindung an
`PluginSandbox` (siehe Abschnitt 5 - gilt unabhängig davon, ob eine Sandbox-Policy konfiguriert
ist).

**Affected Areas**

Neues Package `org.pcsoft.framework.pluggiat.persistence.integrity`, keine Änderung an bestehenden
`PluginPersistenceStrategy`-Implementierungen selbst (reiner Decorator).

**Dependencies**

Keine (verwendet keinen von IP-01 eingeführten Typ - `IntegrityProtectedPersistenceStrategy` ist
ein reiner `PluginPersistenceStrategy`-Decorator ohne Bezug zu `PluginSandbox`/
`PluginSandboxPolicy`; wird im selben Feature Plan geführt, da beide denselben
Bedrohungshintergrund adressieren, nicht wegen einer Code-Abhängigkeit).

**Expected Result**

Ein Plugin (oder ein Dritter), das/der die Persistenzdatei direkt manipuliert, ohne den passenden
HMAC mitzuändern, erreicht beim nächsten `read` nicht den gefälschten Wert, sondern `null` - die
Manipulation wird erkannt statt unbemerkt wirksam zu werden. In Kombination mit der Sandbox aus
IP-02 ist der Schlüssel für ein In-VM-Plugin zusätzlich gar nicht erst erreichbar.

**Technical Considerations**

Dies ist eine Erschwerung/Erkennung, **keine** Garantie gegen Code, der im selben Prozess und
unter demselben OS-Benutzer wie der Host läuft (siehe Abschnitt 9) - ein hinreichend privilegiertes
Plugin könnte theoretisch auch den HMAC selbst neu berechnen, wenn es sowohl Schlüssel als auch
Berechnungslogik erreichen kann. Der Schutz ist daher explizit als zusätzliche Schicht zu verstehen,
die zusammen mit IP-02 wirkt, nicht als eigenständige harte Garantie. Bewusst kein Angebot einer
OS-Benutzertrennung des Host-Prozesses selbst - das wurde vom Nutzer explizit abgelehnt, um keine
zusätzlichen Plattform-Abhängigkeiten und Betriebskomplexität einzuführen.

### IP-07: Checksum-/Signatur-Härtung (Byte-Pinning) (COMPLETED)

**Objective**

Die TOCTOU-Lücke zwischen Sicherheitsprüfung (`PluginScanner.applySecurityCheck`) und tatsächlichem
Laden (`PluginLoader.load()`) strukturell schließen, indem beide Schritte auf denselben, einmalig
gelesenen Bytes arbeiten; den Checksum-Digestvergleich auf einen zeitkonstanten Vergleich
umstellen; sicherstellen, dass Verifikation und Laden dieselbe ZIP/JAR-Eintrags-Interpretation
verwenden (keine Duplicate-Entry-Divergenz); die Signaturprüfung um eine
Zertifikats-Gültigkeitsprüfung ergänzen. Betrifft bestehenden Code, unabhängig vom Rest dieses
Features.

**Scope**

Enthalten: `PinnedPluginContent`-Typ (Rohbytes eines Kandidaten, einmalig gelesen); neue
`PluginSecurityStrategy.check`-Überladung, die gegen `PinnedPluginContent` statt gegen einen `Path`
prüft (`ChecksumSecurityStrategy`, `SignatureSecurityStrategy` entsprechend angepasst);
zeitkonstanter Digestvergleich über `MessageDigest.isEqual` statt `String.equals` in
`ChecksumSecurityStrategy`; eine einzige, gemeinsame `resolveJarEntries`-Funktion für die
ZIP/JAR-Eintragsauflösung, verwendet sowohl von `SignatureSecurityStrategy` als auch vom neuen
`PinnedPluginClassLoader` (schließt die Duplicate-Entry-Divergenz strukturell); Aufruf von
`certificate.checkValidity()` in `SignatureSecurityStrategy` für jedes verwendete Zertifikat, ohne
CRL/OCSP; `PinnedPluginClassLoader` als speicherbasierte Alternative zu `PluginClassLoader` (liest
Klassen/Ressourcen ausschließlich aus den gepinnten Bytes, kein erneuter Festplattenzugriff); neue
`PluginLoader.load`-Überladung, die `PinnedPluginContent` entgegennimmt; `PluginScanResult` trägt
das `PinnedPluginContent` bis zum Laden weiter; `PluginManager.scan()`/`reactivate()` nutzen
durchgängig den gepinnten Pfad für frisch geprüfte Kandidaten. Nicht enthalten: Widerrufsprüfung
(CRL/OCSP), `CertPathValidator`/PKIX-Trust-Anchor-Aufbau, Änderungen an der übrigen
Sicherheitslogik/den Prüfregeln selbst.

**Affected Areas**

`PluginSecurityStrategy`, `ChecksumSecurityStrategy`, `SignatureSecurityStrategy`, `PluginSecurity`,
`PluginScanner`, `PluginScanResult`, `PluginLoader`, neues Hilfsmodul
`org.pcsoft.framework.pluggiat.classloader.jar` (gemeinsame Zip/Jar-Eintragsauflösung), neuer
`PinnedPluginClassLoader` (ersetzt `PluginClassLoader` als primären Ladepfad für frisch geprüfte
Kandidaten), `LoadedPlugin`, `PluginManager` (`scan`, `reactivate`).

**Dependencies**

Keine (eigenständige, von der Sandbox-Fassade unabhängige Härtung; wird im selben Feature Plan
geführt, da sie denselben "Sicherheit des Ladevorgangs"-Themenkomplex betrifft).

**Expected Result**

Ein Kandidat, dessen Bytes zwischen Sicherheitsprüfung und Laden auf der Platte ausgetauscht werden,
wird nachweislich mit den ursprünglich geprüften Bytes geladen, nicht mit den ausgetauschten - die
TOCTOU-Lücke aus Abschnitt 2 existiert nicht mehr. Der Checksum-Vergleich ist zeitkonstant. Ein JAR
mit doppelten ZIP-Eintragsnamen wird von Verifikation und Ladevorgang identisch interpretiert. Ein
signierter Kandidat mit abgelaufenem Zertifikat wird als Sicherheitsproblem erkannt.

**Technical Considerations**

Größter Eingriff dieses Plans: `PluginClassLoader` ist heute ein `URLClassLoader`, der Klassen bei
Bedarf lazy von der Platte nachlädt; ein rein speicherbasierter `PinnedPluginClassLoader` muss
sämtliche JAR-Einträge beim Erstellen einmalig über die gemeinsame `resolveJarEntries`-Funktion
indizieren und `findClass`/`findResource` vollständig aus dem Speicher bedienen. Konsequenzen, die
im Detailplan explizit zu adressieren sind:
- **Speicherverbrauch**: die gepinnten Rohbytes müssen für die gesamte Lebensdauer des geladenen
  Plugins im Speicher gehalten werden (nicht nur für die Dauer des Ladevorgangs), da Klassen lazy
  nachgeladen werden können - bei sehr großen Plugin-JARs ein spürbarer Mehrverbrauch gegenüber
  dem bisherigen dateibasierten Zugriff.
- **Native Bibliotheken**: `System.loadLibrary`/`System.load` aus einem Plugin heraus setzen eine
  echte Datei auf der Platte voraus; ein rein speicherbasierter Classloader kann das nicht ohne
  Weiteres bedienen. Sofern das Framework das heute überhaupt unterstützt, ist dies als Einschränkung
  zu dokumentieren, nicht stillschweigend zu brechen.
- **Ressourcen mit Datei-Erwartung**: JAR-Manifest-Attribute oder Plugin-Code, der über
  `getResource()` eine echte `file:`-URL erwartet (statt nur den Inhalt zu lesen), funktioniert mit
  einem speicherbasierten Loader anders - muss im Detailplan geprüft werden.
- **Multi-JAR-Ordner-Kandidaten**: alle JARs des Ordners müssen beim Pinning vollständig gelesen
  werden, nicht nur das Manifest-JAR - konsistent mit dem bestehenden Checksum-Verfahren dort.
- **Duplicate-Entry-Auflösung**: die gemeinsame `resolveJarEntries`-Funktion muss bei doppelten
  Eintragsnamen ein wohldefiniertes, dokumentiertes Verhalten zeigen (z. B. konsistent mit dem
  JDK-eigenen ZIP-Verhalten "letzter Eintrag gewinnt"), damit Verifikation und Laden garantiert
  denselben Bytes je Klassenname zuordnen.
- **Gültigkeitsprüfung ohne Zeitstempel**: Da keine vertrauenswürdige Signierzeit (RFC 3161)
  geprüft wird, verwendet `checkValidity()` implizit den aktuellen Systemzeitpunkt - ein zum
  Signierzeitpunkt gültiges, inzwischen aber abgelaufenes Zertifikat lässt eine alte, ursprünglich
  legitime Signatur ab dem Ablaufdatum fehlschlagen. Das ist als bewusste, einfache Lösung zu
  dokumentieren, nicht als Fehler zu behandeln.

**Tatsächlich umgesetzt**

Wie geplant, mit zwei Abweichungen:
- `PluginSecurity` hat zusätzlich eine neue Methode `reevaluateAndPin(location, path,
  defaultSecurityChains)` erhalten, statt `reactivate()` selbst neu scannen und pinnen zu lassen -
  sie pinnt die Kandidatenbytes einmalig und prüft sie gegen dieselbe gepinnte Fassung, bevor
  `PluginManager.reactivate()` mit genau diesem `PinnedPluginContent` lädt. Die bestehenden
  öffentlichen Signaturen von `evaluate`/`reevaluate` bleiben dabei unverändert erhalten (rein
  additiv), da beide direkt von `PluginSecurityTest` getestet werden.
- Die kryptografische Signaturprüfung (`SignatureSecurityStrategy.verifyJarSignature`) liest weiterhin
  direkt über `JarInputStream` aus den gepinnten Bytes, nicht über `resolveJarEntries` - die
  JDK-eigene Codesigner-Verifikation ist an den `JarInputStream`/`JarFile`-Mechanismus gebunden und
  lässt sich nicht auf eine bereits flach aufgelöste Entry-Map übertragen. `resolveJarEntries` wird
  innerhalb der Signaturprüfung nur zum Auffinden der Manifest-JAR und der Checksum-Liste einer
  `MultiJarWithOwnFolderScanStrategy`-Kandidatur eingesetzt (dort, wo zuvor `JarFile.getJarEntry`
  verwendet wurde) - das schließt weiterhin die in Abschnitt 2 beschriebene
  Duplicate-Entry-Divergenz für diese beiden Stellen.

### IP-08: Kollisionsauflösung nach Sicherheitsstatus filtern (COMPLETED)

**Objective**

Verhindern, dass ein Plugin-Kandidat mit fehlgeschlagener Sicherheitsprüfung (Status
`SECURITY_PROBLEM`) einen bereits erfolgreich geladenen Kandidaten derselben Plugin-`id` durch eine
höhere deklarierte Manifest-Version aus der Kollisionsauflösung verdrängt. Betrifft bestehenden
Code (`IdCollisionResolver`), unabhängig vom Rest dieses Features.

**Scope**

Enthalten: Anpassung von `IdCollisionResolver.resolve()`/`resolveGroup()`, sodass innerhalb einer
Id-Kollisionsgruppe nur Kandidaten mit Status `LOADED` um die "beste" Version konkurrieren;
Kandidaten mit einem anderen Status (z. B. `SECURITY_PROBLEM`) werden aus dem Versionsvergleich
herausgenommen und behalten ihren ursprünglichen Status unverändert, statt auf `ID_COLLISION`
überschrieben zu werden; besteht eine Gruppe ausschließlich aus Nicht-`LOADED`-Kandidaten, findet
gar keine Kollisionsauflösung statt (nichts zu verdrängen). Nicht enthalten: Änderungen an
`MinVersionChecker`, an der eigentlichen Versionsvergleichslogik (`ComparableVersion`) für
tatsächlich konkurrierende `LOADED`-Kandidaten, oder an der Reihenfolge von Sicherheitsprüfung und
Kollisionsauflösung in `PluginManager.scan()` selbst.

**Affected Areas**

`IdCollisionResolver`.

**Dependencies**

Keine (eigenständige, von der Sandbox-Fassade und von IP-07 unabhängige Korrektur; wird im selben
Feature Plan geführt, da sie denselben "Sicherheit der Ladepipeline"-Themenkomplex betrifft).

**Expected Result**

Ein Kandidat mit fehlgeschlagener Sicherheitsprüfung kann einen bereits erfolgreich geladenen
Kandidaten derselben `id` nicht mehr per Versions-Spoofing als `ID_COLLISION` verwerfen lassen. Das
bestehende Verhalten für echte Mehrfach-`LOADED`-Kollisionen (höhere Version gewinnt, Gleichstand
lehnt beide ab) bleibt unverändert.

**Technical Considerations**

Kleiner, gut eingrenzbarer Eingriff im Vergleich zu den übrigen Plänen dieses Features. Bestehende
Tests (`IdCollisionResolverTest`, `IdCollisionAndMinVersionIntegrationTest`) decken das heutige
Verhalten ab und müssen um Testfälle ergänzt werden, die genau dieses Szenario abdecken (ein
`SECURITY_PROBLEM`-Kandidat mit höherer Version neben einem `LOADED`-Kandidaten derselben `id`) -
`testing`-Skill vor der Testklassen-Änderung laden.

**Tatsächlich umgesetzt**

Wie geplant, ohne Abweichung: `IdCollisionResolver.resolve()` bildet die `byId`-Gruppierung nur noch
aus Kandidaten mit `status == LOADED`; Kandidaten mit anderem Status werden über eine separate
`notLoaded`-Liste unverändert durchgereicht. `IdCollisionResolverTest` und
`IdCollisionAndMinVersionIntegrationTest` wurden je um einen Testfall ergänzt, der einen
`SECURITY_PROBLEM`-Kandidaten mit höherer Version neben einem `LOADED`-Kandidaten prüft.

## 8. Dependency Graph

```text
IP-01 (COMPLETED)
├── IP-02 (COMPLETED)
│   └── IP-05
├── IP-03
│   └── IP-05
└── IP-04

IP-06 (COMPLETED) (eigenständig, keine Code-Abhängigkeit zu IP-01 - reiner Persistenz-Decorator)
IP-07 (COMPLETED) (eigenständig, keine Abhängigkeit zu IP-01..IP-06)
IP-08 (COMPLETED) (eigenständig, keine Abhängigkeit zu IP-01..IP-07)
```

## 9. Risks and Open Questions

* **Kein `SecurityManager` verfügbar** (JDK 25 / JEP 486): jede In-VM-Durchsetzung basiert auf
  Bytecode-Instrumentierung und Konvention, nicht auf einem vom JDK garantierten
  Permission-Modell.
* **Java-Agent erfordert Host-Kooperation** (JDK 21+/JEP 451): ohne `-javaagent`-Start-Parameter
  oder explizit freigeschaltetes dynamisches Attachment (`-XX:+EnableDynamicAgentLoading`) kann
  `pluggiat` den Agenten nicht selbstständig aktivieren - dies muss mit dem Nutzer als
  Host-Integrationsanforderung geklärt werden, bevor IP-02 begonnen wird.
* **Restlücke trotz Agent**: Bytecode, der eine riskante JDK-Klasse referenziert, bevor der Agent
  registriert ist (sehr frühe Klasseninitialisierung), kann nicht rückwirkend erfasst werden. Dies
  ist eine grundsätzliche Grenze, kein Implementierungsdetail, und muss dem Nutzer vor Beginn von
  IP-02 klar kommuniziert werden.
* **Kein hartes Thread-Abbrechen ohne Prozessgrenze**: `Thread.stop()` ist unsicher; IP-03 kann
  Fehlverhalten erkennen, aber nur IP-04 (Prozessisolation) kann es garantiert beenden.
* **Umfang des Bouncy-Castle-Einsatzes für IP-04** ist ungeklärt: welche Teile der bestehenden
  Extension-Point-API (`ExtensionConfiguration`, Extension-Interfaces) sich sinnvoll auf die
  verwendeten ASN.1-Typen abbilden lassen, und welches konkrete Bouncy-Castle-Artefakt (z. B. nur
  `bcprov-jdk18on` für reines ASN.1) minimal ausreicht, muss vor dem Detailplan zu IP-04 geklärt
  werden.
* **Bewusst keine OS-Prozess-/Benutzertrennung** (IP-04 und IP-06): Der Nutzer hat entschieden, auf
  eine harte, vom Betriebssystem erzwungene Trennung (z. B. Windows Restricted Tokens/Integrity
  Levels als Äquivalent zu Unix-Benutzerwechsel) bewusst zu verzichten, um keine zusätzlichen
  Plattform-Abhängigkeiten (JNA/JNI für Windows) und Betriebskomplexität einzuführen. Konsequenz:
  Weder die Prozessisolation (IP-04) noch der Persistenz-Integritätsschutz (IP-06) bieten eine
  harte Garantie gegen Code, der im selben Prozess/unter demselben OS-Benutzer wie der Host läuft -
  beide sind Erschwerung/Erkennung, keine Garantie. Dies ist eine bewusste, vom Nutzer getroffene
  Design-Entscheidung und kein technisches Versäumnis; sie muss im MkDocs-Sicherheitskapitel
  explizit so benannt werden (siehe Feature Completion Criteria).
* **Speicherverbrauch und Funktionsumfang des Byte-Pinnings** (IP-07): gepinnte Rohbytes bleiben
  für die gesamte Plugin-Lebensdauer im Speicher; native Bibliotheken und dateipfad-abhängige
  Ressourcenzugriffe aus Plugin-Code könnten mit einem rein speicherbasierten Classloader nicht
  mehr funktionieren wie zuvor - der tatsächliche Funktionsumfang muss vor dem Detailplan zu IP-07
  geprüft und dokumentiert werden.
* **Bewusst keine Widerrufsprüfung (CRL/OCSP)** (IP-07): Eine echte Widerrufsprüfung würde eine
  betriebene PKI-Infrastruktur voraussetzen (CRL-Distributionspunkte oder OCSP-Responder), die für
  selbstsignierte, per `PublicKeyProviderStrategy` gepinnte Zertifikate typischerweise nicht
  existiert. Ergänzt wird nur die Gültigkeitszeitraum-Prüfung (`notBefore`/`notAfter`), die ohne
  externe Infrastruktur auskommt. Ein kompromittierter, aber noch gültiger Schlüssel bleibt bis zu
  seinem regulären Ablauf vertrauenswürdig - dies ist eine bewusste, vom Nutzer akzeptierte Grenze,
  kein technisches Versäumnis.
* **Performance-Overhead**: Bytecode-Instrumentierung (IP-02), dedizierte Executors (IP-03),
  Bouncy-Castle-ASN.1-BER-IPC (IP-04), HMAC-Berechnung pro Persistenzzugriff (IP-06) und
  vollständiges Einlesen der Kandidatenbytes vor dem Laden (IP-07) fügen Latenz/Speicherbedarf
  hinzu; das Ausmaß muss vor der Implementierung abgeschätzt werden, damit Hosts mit
  performancekritischen Extension-Aufrufen die Sandbox gezielt abschalten können.
* **Abwärtskompatibilität**: Standardverhalten ohne konfigurierte Sandbox-Policy muss dem heutigen
  Zustand (keine Einschränkung) entsprechen, analog zum expliziten Charakter von
  `InsecureSecurityStrategy` - eine Sandbox darf nicht implizit aktiv werden. Der
  Persistenz-Integritätsschutz (IP-06) ist ebenfalls ein Decorator, den ein Host explizit
  einsetzen muss, keine automatische Änderung bestehender `PluginPersistenceStrategy`-Nutzung.
  IP-07 ändert dagegen den Ladepfad für frisch geprüfte Kandidaten grundlegend - dies ist kein rein
  additiver Baustein und muss im Detailplan besonders sorgfältig gegen bestehende Tests
  (`PluginLoaderTest`, `PluginLoaderTestFixtures`) abgesichert werden. Ein bereits akzeptiertes,
  inzwischen abgelaufenes Signaturzertifikat führt nach IP-07 zu einem neuen Fehlschlag, wo zuvor
  keiner war - dies ist eine gewollte Verhaltensänderung, die dem Nutzer vor Beginn von IP-07
  explizit zu kommunizieren ist.
* **Kollisionsauflösung vor IP-08 (COMPLETED)**: Bis zur Umsetzung von IP-08 konnte ein Kandidat mit
  fehlgeschlagener Sicherheitsprüfung einen bereits erfolgreich geladenen Kandidaten derselben
  Plugin-`id` per höherer deklarierter Version aus der Kollisionsauflösung verdrängen
  (Downgrade-/DoS-Vektor, siehe Abschnitt 2) - mit IP-08 behoben: `IdCollisionResolver` lässt nur
  noch `LOADED`-Kandidaten um die Version konkurrieren.
* **Fremdabhängigkeiten**: die Bytecode-Umschreibe-Bibliothek für IP-02 (z. B. ASM) ist laut
  `dependencies.md` weiterhin separat mit dem Nutzer abzustimmen. Für IP-04 ist **Bouncy Castle**
  bereits vom Nutzer als Fremdabhängigkeit bestätigt. IP-06, IP-07 und IP-08 benötigen keine neue
  Fremdabhängigkeit (reine JDK-Bordmittel bzw. reine Logikänderung).

## 10. Feature Completion Criteria

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
  Bouncy-Castle-basierte ASN.1-BER-TLV-Protokoll läuft, und dessen Extensions über die normale
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
* Sandbox-Verstöße sind über dieselbe Beobachtungs-/Persistenzschicht nachvollziehbar wie
  bestehende Sicherheits- und Deaktivierungsgründe.
* Die dokumentierten strukturellen Grenzen (kein `SecurityManager`, Host-Voraussetzung für den
  Java-Agent, kein hartes In-VM-Thread-Abbrechen, Bouncy-Castle-Abhängigkeit für IP-04, bewusster
  Verzicht auf OS-Prozess-/Benutzertrennung bei IP-04/IP-06, Speicher-/Funktionsumfang-Tradeoffs
  des Byte-Pinnings sowie bewusster Verzicht auf Widerrufsprüfung bei IP-07) sind im
  MkDocs-Sicherheitskapitel als bewusste Design-Entscheidung festgehalten, nicht verschwiegen.
