# Laufzeit-Sandbox

`PluginSandbox` ist die host-weite Fassade für die *Laufzeit*-Sandbox eines Plugins - eine Schicht
oberhalb der [Sicherheitskette](security.de.md) vor dem Laden, die steuert, was ein bereits
geladenes Plugin zur Laufzeit tun darf.

!!! warning "Benötigt den JVM-Start-Parameter `-javaagent`"

    Sobald eine konfigurierte `PluginSandboxPolicy` mindestens eine API-Kategorie einschränkt, MUSS
    die Host-JVM mit dem eigenen JAR dieses Moduls als Java-Agent gestartet werden - andernfalls
    bricht der Host in dem Moment, in dem eine solche Policy aktiviert wird, mit einer
    `SandboxAgentNotActiveException` ab:

    ```text
    java -javaagent:pluggiat-<version>.jar -jar my-host-app.jar
    ```

    Details siehe [API-Mediation aktivieren](#api-mediation-aktivieren-javaagent) unten.

## Eine Policy konfigurieren

```kotlin
val manager = pluginManager {
    defaultSandboxPolicy {
        type = PluginLocationType.EXTERNAL
        policy = PluginSandboxPolicy(
            allowedApiCategories = setOf(SandboxApiCategory.THREAD_CREATION),
        )
    }
    location {
        path = Paths.get("/opt/myapp/plugins")
        type = PluginLocationType.EXTERNAL
        sandboxOverride = PluginSandboxPolicy.UNRESTRICTED // schaltet diese eine Location wieder frei
    }
}
```

* `sandboxPolicies` (über `defaultSandboxPolicy { type = ...; policy = ... }`) - die Standard-Policy
  je `PluginLocationType`.
* Der eigene `sandboxOverride` einer Location gewinnt immer gegenüber dem Standard für ihren `type`.
* Ganz ohne Konfiguration (Standard) gilt `PluginSandboxPolicy.UNRESTRICTED` - vollständig
  freigegeben, kein Java-Agent nötig.

`PluginSandboxPolicy.allowedApiCategories` listet die `SandboxApiCategory`-Werte, die ein Plugin
unter dieser Policy direkt nutzen darf. Jede Kategorie, die *nicht* in dieser Menge enthalten ist,
wird an der abgesicherten Aufrufstelle blockiert.

## Was jede Kategorie abdeckt

| Kategorie | Abgesicherte APIs |
|-----------|-------------------|
| `FILESYSTEM` | `java.io`-Dateitypen (`File` bei *jedem* Member, `FileInputStream`/`FileOutputStream`/`FileReader`/`FileWriter`/`RandomAccessFile`, `FileDescriptor`); das vollständige `java.nio.file`-Gegenstück (`Files`, `Paths`, `FileSystem`/`FileSystems`, `DirectoryStream`, `WatchService`) samt der darunterliegenden SPI `java.nio.file.spi.FileSystemProvider`; `FileChannel`/`AsynchronousFileChannel`; die dateisystemberührenden `Path`-Member (`toRealPath`, `register`, `toFile`); dateiöffnende Konstruktoren von `PrintStream`/`PrintWriter`/`Scanner`/`Formatter`; `ZipFile`, `JarFile`, `ImageIO`, `FileHandler` |
| `NETWORK` | `Socket`, `ServerSocket`, `DatagramSocket`, `MulticastSocket` (je bei *jedem* Member), `URLConnection`/`HttpURLConnection`/`JarURLConnection`, `URL.openConnection`/`openStream`/`getContent`, `java.net.http.HttpClient`, die `java.nio.channels`-Netzwerkkanäle samt der darunterliegenden `java.nio.channels.spi`, die Socket-Factories aus `javax.net`/`javax.net.ssl`, DNS-Auflösung über `InetAddress`, `NetworkInterface`, `java.rmi` und `javax.naming` (JNDI) |
| `REFLECTION` | die vollständigen Pakete `java.lang.reflect` und `java.lang.invoke` (`Field.get`/`set`/`setAccessible`, `Constructor.newInstance`, `Proxy`, `MethodHandle.invoke`, `VarHandle`, `MethodHandles.privateLookupIn`), die reflektiven `java.lang.Class`-Member (`forName`, `getDeclared*`, `getClassLoader`, …), `ClassLoader.defineClass`/`loadClass`, `ObjectInputStream` (Deserialisierung), `sun.misc.Unsafe`/`jdk.internal.misc.Unsafe` |
| `PROCESS_START` | `ProcessBuilder`, `Process`, `ProcessHandle`, `Runtime.exec`/`halt`/`addShutdownHook`, `System.exit` sowie das Laden nativen Codes (`System.load`/`loadLibrary`) |
| `THREAD_CREATION` | Erzeugen/Starten von `Thread` (auch virtuelle Threads), `ThreadGroup`, `Executors`, Konstruktion von `ThreadPoolExecutor`/`ScheduledThreadPoolExecutor`/`ForkJoinPool`/`Timer`, `ForkJoinPool.commonPool`, die asynchronen `CompletableFuture`-Stufen |

Die Absicherung beschränkt sich nicht auf die wörtlich genannten Typen:

* **Subklassen sind erfasst.** Definiert ein Plugin `class MyFile : File` und ruft `myFile.delete()`
  auf, greift der Guard ebenfalls - der Agent löst die Typhierarchie der Aufrufstelle auf und wendet
  den Guard des Basistyps an, inklusive dessen Member-Unterscheidungen (eine `Thread`-Subklasse wird
  bei `start()` blockiert, darf aber weiter `currentThread()` aufrufen).
* **Reflektive und indirekte Zugriffe sind erfasst.** Sowohl Reflection à la `Method.invoke` als auch
  Methoden*referenzen* (`Files::readAllBytes`, die keine direkte Aufrufinstruktion erzeugen) werden
  abgesichert.
* **Harmlose Member bleiben bewusst frei**, damit ein eingeschränktes Plugin normal arbeiten kann:
  `System.out.println`, In-Memory-`java.io`-Streams, Pfadarithmetik (`resolve`, `getFileName`),
  `URI`/`URLEncoder`, `Class.getName`, `ClassLoader.getResourceAsStream` (Lesen einer Ressource aus dem
  eigenen JAR des Plugins), `Thread.currentThread`/`sleep`, `Runtime.availableProcessors`. Da ein
  blockierter Aufruf das Plugin dauerhaft als `POTENTIAL_ATTACK` markiert (siehe unten), wäre ein
  Fehlalarm nicht rückholbar - deshalb werden ganze Pakete nur dort abgesichert, wo es keinen
  harmlosen Member gibt.

## API-Mediation aktivieren (`-javaagent`)

Die Bytecode-API-Mediation ist als Java-Agent (`PluginSandboxAgent`) umgesetzt, da JDK 25
`SecurityManager`/`AccessController` entfernt hat (JEP 486) - es gibt keinen In-VM-Berechtigungs-
mechanismus mehr, auf dem aufgebaut werden könnte. Der Agent instrumentiert jede Klasse, die über den
`PluginClassLoader` eines Plugins geladen wird, und fügt vor jedem riskanten JDK-Aufruf einen
Guard-Check ein.

Das eigene Build-Artefakt dieses Moduls **ist** der Agent - sein Manifest deklariert bereits
`Premain-Class`/`Agent-Class`. `-javaagent` muss auf das JAR zeigen, zu dem euer Build es auflöst
(z. B. das Fat/Shadow-JAR der Host-Anwendung oder direkt das aufgelöste Abhängigkeits-JAR):

```text
java -javaagent:/pfad/zu/pluggiat-<version>.jar -jar my-host-app.jar
```

Dynamisches Nachladen nach dem JVM-Start (Attach-API) wird bewusst **nicht** unterstützt - JDK 21+
schränkt dies standardmäßig ein (JEP 451) und würde vom Host zusätzlich
`-XX:+EnableDynamicAgentLoading` verlangen, was einen erforderlichen Parameter nur gegen einen
anderen mit weniger Garantien tauscht (siehe [Einschränkungen](#einschrankungen) unten).

Wird eine `PluginSandboxPolicy`, die Mediation verlangt, ohne installierten Agenten aktiviert, wirft
der Plugin-Manager sofort eine `SandboxAgentNotActiveException` und bricht den Start ab - das ist
gewollt: Ein Host, der eine restriktive Policy konfiguriert hat, muss das sofort erfahren, statt
später festzustellen, dass Plugins völlig ungeschützt liefen.

## Sandbox-Verstöße und `POTENTIAL_ATTACK`

Ein blockierter Aufruf schlägt nicht einfach nur stillschweigend fehl:

1. Er wird als `WARN` protokolliert ("SECURITY WARNING - potential attack: ...").
2. Das betroffene Plugin wird sofort zwangsweise entladen, ohne dass seine `onDisable`/
   `onUnload`-Hooks aufgerufen werden (einem Plugin, das gerade die Sandbox angegriffen hat, wird
   nicht mehr zugetraut, weiteren eigenen Code auszuführen).
3. Sein Eintrag in `PluginManager.scanResults` wird als `PluginScanStatus.POTENTIAL_ATTACK` markiert -
   bewusst getrennt von `SECURITY_PROBLEM`: Letzteres ist ein Befund vor dem Laden zu einem
   Kandidaten, der nie lief, dies hier ist ein Laufzeitbefund zu einem Plugin, das bereits
   ausgeführt wurde.
4. Der Verstoß wird als `SandboxViolationException` an
   `PluginManagerConfiguration.exceptionHandlingStrategy` weitergereicht, damit der Host informiert
   ist.

Ein `POTENTIAL_ATTACK`-Kandidat kann **nie** erneut erzwungen geladen werden (`PluginManager.forceLoad`
wirft `IllegalStateException`) und kann nie einen `LOADED`-Kandidaten derselben Plugin-Id über den
`IdCollisionResolver` verdrängen - anders als jeder andere Nicht-`LOADED`-Status gibt es dafür keine
Host-Überschreibung.

## Sicherheitsempfehlungen

!!! tip "Sicherheitsempfehlungen"

    * Mit den restriktivsten `allowedApiCategories` beginnen, die die eigenen Plugins tatsächlich
      benötigen, und bewusst erweitern - nicht umgekehrt.
    * Für `EXTERNAL`-Verzeichnisse immer eine Policy konfigurieren; der unkonfigurierte Standard
      (`PluginSandboxPolicy.UNRESTRICTED`) gewährt vollen Zugriff und benötigt keinen Agenten.
    * Dies mit einer signaturbasierten [Sicherheitskette](security.de.md) kombinieren - die Sandbox
      schränkt ein, was ein bereits geladenes Plugin tun kann, sie prüft nicht, wer es erzeugt hat.
    * Einen `POTENTIAL_ATTACK`-Status als Vorfall behandeln, nicht als Routineablehnung: anders als
      bei `SECURITY_PROBLEM` gibt es bewusst keine Überschreibung - vor einer erneuten Verteilung
      dieses Plugin-Builds erst untersuchen.
    * Die Lücke bei sehr früher Klasseninitialisierung unten im Blick behalten - die Mediation ist
      stark, aber nicht absolut.

## Einschränkungen

* **Nur der eigene Code des Plugins wird instrumentiert.** Der Agent transformiert Klassen, die über
  den `PluginClassLoader` eines Plugins geladen werden. Code, den das Plugin lediglich *aufruft* - die
  eigenen SDK-Klassen des Hosts, alles über die [SDK-Whitelist](sdk-whitelist.de.md) Freigegebene -,
  wird nicht instrumentiert; eine Host-Methode, die eine riskante Operation im Auftrag des Plugins
  ausführt, ist also nicht abgesichert. Die freigegebene Oberfläche sollte daher keine Methoden
  enthalten, die einen beliebigen Pfad, eine URL oder einen Klassennamen vom Aufrufer übernehmen.
* **Sehr frühe Klasseninitialisierung**: Bytecode, der eine riskante JDK-Klasse referenziert, bevor
  der Agent registriert ist (z. B. in einem `<clinit>`, das während des Klassenladens selbst läuft),
  kann nicht rückwirkend erfasst werden.
* **Nativer Code liegt vollständig außerhalb der Reichweite** einer Bytecode-Instrumentierung. Sein
  Laden ist deshalb als `PROCESS_START` abgesichert - ein Plugin, dem das Laden nativen Codes erlaubt
  ist, ist faktisch nicht mehr sandboxed.
* **Thread-/Zeitlimit-Governance und Prozessisolation** sind eigene, spätere Teile der
  Laufzeit-Sandbox und nicht durch API-Mediation allein abgedeckt - `THREAD_CREATION` blockiert das
  *Erzeugen* von Threads, begrenzt aber nicht die Laufzeit eines bereits laufenden.
