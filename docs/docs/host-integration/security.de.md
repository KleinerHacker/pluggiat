# Sicherheit

Jedes `PluginLocation` ist durch eine geordnete, frei erweiterbare Fallback-Kette von
`PluginSecurityStrategy`-Implementierungen geschützt. Jeder gescannte Kandidat (sobald sein Manifest
gültig ist) wird gegen die Kette geprüft: Die erste erfolgreiche Strategie beendet die Prüfung
positiv; ein `SECURITY_PROBLEM` wird erst gemeldet, wenn **jede** Strategie der Kette
fehlgeschlagen ist.

## Welche Strategie sollte ich verwenden?

* **Kein Schutz erforderlich** (z. B. ein `BUILTIN`-Verzeichnis, das Sie vollständig
  kontrollieren) - `InsecureSecurityStrategy`.
* **Sie kontrollieren den Signierschlüssel und können bei jedem Release neu signieren** -
  `SignatureSecurityStrategy`. Stärkste Garantie: bestätigt, dass der Kandidat von jemandem
  erzeugt wurde, der den privaten Schlüssel besitzt, nicht nur, dass er unverändert ist.
* **Sie können Releases nicht signieren, möchten aber unerwartete Änderungen an einer sonst
  vertrauenswürdigen Datei erkennen** (z. B. ein Plugin, das ein Benutzer einmal manuell
  genehmigt hat) - `ChecksumSecurityStrategy`.
* **Mehrstufiger Schutz (Defense in Depth)** - mehrere Strategien für dasselbe Verzeichnis
  verketten; die erste, die erfolgreich ist, gewinnt, sodass z. B. `SignatureSecurityStrategy`
  gefolgt von `ChecksumSecurityStrategy` entweder einen korrekt signierten Kandidaten oder einen
  akzeptiert, dessen Prüfsumme zuvor genehmigt wurde.

## Kein impliziter Standard

Es gibt keinen impliziten "keine Prüfung"-Standard. Die effektive Kette eines Verzeichnisses ist:

1. Sein eigener `securityOverride`, sofern nicht leer.
2. Andernfalls der `defaultSecurityChains`-Eintrag des `PluginScanner`s für den `type` des
   Verzeichnisses.

Sind beide leer, wirft `PluginScanner.scan` eine `IllegalStateException` - es muss immer eine Kette
konfiguriert sein, einschließlich explizit `InsecureSecurityStrategy`, falls für einen gegebenen
Verzeichnistyp tatsächlich keine Prüfung gewünscht ist:

```kotlin
val scanner = PluginScanner(
    defaultSecurityChains = mapOf(
        PluginLocationType.BUILTIN to listOf(InsecureSecurityStrategy()),
        PluginLocationType.EXTERNAL to listOf(
            SignatureSecurityStrategy(myPublicKeyProviderStrategy),
            ChecksumSecurityStrategy(myPersistenceStrategy),
        ),
    ),
)
```

## Prüfsummenalgorithmen

Sowohl die Prüfsummenliste von `SignatureSecurityStrategy` (für
`MultiJarWithOwnFolderScanStrategy`, siehe unten) als auch `ChecksumSecurityStrategy` berechnen
Prüfsummen über einen pluggbaren
`org.pcsoft.framework.pluggiat.security.checksum.ChecksumAlgorithm`:

```kotlin
interface ChecksumAlgorithm {
    val id: String
    fun digest(bytes: ByteArray): String // hex-kodiert
}
```

Die mitgelieferte Implementierung `MessageDigestChecksumAlgorithm` umschließt jeden von der JVM
verstandenen `java.security.MessageDigest`-Algorithmusnamen (z. B. `"MD5"`, `"SHA-256"`,
`"SHA-512"`, ...). Beide Strategien verwenden standardmäßig
`MessageDigestChecksumAlgorithm("SHA-512")`, sofern nicht anders konfiguriert:

```kotlin
val strategy = ChecksumSecurityStrategy(myPersistenceStrategy, algorithm = MessageDigestChecksumAlgorithm("SHA-256"))
```

Ein eigener Algorithmus - z. B. ein Nicht-JCA-Algorithmus oder einer, der von externer Hardware
unterstützt wird - kann auf dieselbe Weise wie eine eigene `PluginSecurityStrategy` eingebunden
werden, ganz ohne Framework-Änderungen: implementieren Sie einfach selbst `ChecksumAlgorithm` und
übergeben Sie ihn dem Konstruktor der Strategie.

## Mitgelieferte Strategien

### `InsecureSecurityStrategy`

Führt keinerlei Prüfung durch; ist immer erfolgreich. Die Benennung "insecure" (unsicher) ist
bewusst gewählt - der Verzicht auf jeden Schutz für ein Verzeichnis muss eine explizite, sichtbare
Entscheidung sein.

### `SignatureSecurityStrategy`

Verlangt, dass der Kandidat mit einem über eine injizierte
`org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy` aufgelösten Schlüssel
signiert ist (siehe [Public-Key-Provider](public-key-providers.md) für die mitgelieferten
Implementierungen), nachgeschlagen anhand der Manifest-`id` des Plugins. Die Verifikation nutzt den
Standard-JAR-Signaturmechanismus der JDK (`jarsigner`/`JarFile(verify = true)`):

* Kandidaten von `SingleJarScanStrategy`/`ZipJarScanStrategy`: die Kandidatendatei selbst (die
  `.jar` oder die `.zip`) muss signiert sein. Das Signieren einer `.zip` verwendet exakt denselben
  Mechanismus wie das Signieren einer `.jar` - eine signierte JAR *ist* strukturell eine speziell
  aufgebaute ZIP, sodass die Dateiendung für die Signaturprüfung keinen Unterschied macht.
* Kandidaten von `MultiJarWithOwnFolderScanStrategy`: die Manifest-JAR innerhalb des
  Kandidatenordners muss signiert sein und zusätzlich einen `META-INF/plugin-checksums.txt`-Eintrag
  enthalten, der für jede andere JAR im Ordner eine Prüfsumme auflistet (über den konfigurierten
  [`ChecksumAlgorithm`](#prufsummenalgorithmen) der Strategie, standardmäßig SHA-512; je eine Zeile
  `<hex-digest>  <dateiname>` mit zwei Leerzeichen, passend z. B. zum Ausgabeformat von
  `sha512sum`); jede aufgeführte Prüfsumme muss mit der tatsächlichen Nachbardatei übereinstimmen.

#### Eine gültige Signatur pro Scan-Strategie erzeugen

Das Signieren erfolgt vollständig mit den eigenen Kommandozeilenwerkzeugen `keytool`/`jarsigner`
der JDK (in jeder JDK enthalten) - es ist kein plugin-spezifisches Tooling erforderlich. Der an
`keytool -genkeypair` übergebene öffentliche Schlüssel ist derjenige, den eine
`PublicKeyProviderStrategy`-Implementierung später für die ID des Plugins auflösen muss (siehe
[Public-Key-Provider](public-key-providers.md)).

**`SingleJarScanStrategy`** - die einzelne JAR des Plugins direkt signieren:

```shell
jarsigner -keystore my-signing.jks -storepass <passwort> plugin-a.jar my-signing-alias
```

**`ZipJarScanStrategy`** - die `.zip` genau wie einen `MultiJarWithOwnFolderScanStrategy`-Ordner
aufbauen (Manifest-JAR + beliebige weitere JARs direkt darin, siehe unten), dann die fertige
`.zip`-Datei selbst mit demselben Befehl signieren, nur eben auf die `.zip` statt auf eine `.jar`
gerichtet:

```shell
jarsigner -keystore my-signing.jks -storepass <passwort> plugin-a.zip my-signing-alias
```

Das funktioniert, weil sich `jarsigner` nur um das ZIP-Containerformat kümmert, nicht um die
Dateiendung - eine signierte JAR *ist* eine ZIP mit hinzugefügter `META-INF/MANIFEST.MF` sowie
Signatureinträgen.

**`MultiJarWithOwnFolderScanStrategy`** - nur die Manifest-JAR (diejenige, die
`META-INF/plugin.yml`/`plugin.yaml` enthält) wird signiert, nicht die anderen JARs im Ordner. Fügen
Sie ihr vor dem Signieren einen `META-INF/plugin-checksums.txt`-Eintrag hinzu, der die Prüfsumme
jeder anderen JAR im Ordner für den jeweils konfigurierten `ChecksumAlgorithm` der Strategie
auflistet (standardmäßig SHA-512, hier mit `sha512sum` gezeigt):

```shell
# innerhalb des Plugin-Ordners, neben plugin-a-manifest.jar und plugin-a-lib.jar
sha512sum plugin-a-lib.jar > plugin-checksums.txt
mkdir -p META-INF && mv plugin-checksums.txt META-INF/
jar uf plugin-a-manifest.jar META-INF/plugin-checksums.txt
jarsigner -keystore my-signing.jks -storepass <passwort> plugin-a-manifest.jar my-signing-alias
```

Konfiguriert der Host `SignatureSecurityStrategy` mit einem anderen `ChecksumAlgorithm`, verwenden
Sie stattdessen den passenden Befehl (z. B. `sha256sum`/`md5sum`) - der zur Erzeugung der
Prüfsummenliste verwendete Algorithmus muss mit dem der Strategie konfigurierten übereinstimmen,
sonst stimmt schlicht jede Prüfsumme nicht überein.

Die Prüfsummenliste muss **vor** dem Signieren hinzugefügt werden, da `jarsigner` sie mit der
Signatur abdecken muss; ändert sich danach eine andere JAR im Ordner, ohne diese gesamte Abfolge
erneut auszuführen, meldet `SignatureSecurityStrategy` eine nicht übereinstimmende Prüfsumme, obwohl
die Signatur der Manifest-JAR selbst technisch noch gültig ist.

### `ChecksumSecurityStrategy`

Vergleicht die tatsächliche Prüfsumme eines Kandidaten (standardmäßig SHA-512, siehe
[Prüfsummenalgorithmen](#prufsummenalgorithmen)) mit dem unter der Manifest-`id` des Plugins und dem
Schlüssel `"checksum"` im konfigurierten [`PluginPersistenceStrategy`](persistence.md)
gespeicherten Wert:

```kotlin
val strategy = ChecksumSecurityStrategy(persistenceStrategy = myPersistenceStrategy)
```

Sowohl eine nicht aufgelöste erwartete Prüfsumme (`null`, z. B. noch nie gesehen) als auch eine
Abweichung (z. B. die Plugin-Datei hat sich geändert) werden identisch als **fehlgeschlagene**
Prüfung behandelt - das Framework kennt keinen separaten "ausstehend"-Zustand.

!!! note "Migrationshinweis (Breaking Change)"

    Vor IP-06 nahm `ChecksumSecurityStrategy` einen separaten `ExpectedChecksumCallback` entgegen,
    und eine genehmigte Prüfsumme wurde über einen eigenen `ChecksumPersistenceCallback`
    aufgezeichnet. Beide wurden zugunsten der einzigen, generischen
    [`PluginPersistenceStrategy`](persistence.md) (Schlüssel `"checksum"`) entfernt, die nun auch
    den aktiviert/deaktiviert-Status der Plugins trägt.

## Host-Freigabeablauf nach einem `SECURITY_PROBLEM`

Es gibt keinen Status `PENDING_APPROVAL` im Framework. Ob und wie auf ein
`PluginScanStatus.SECURITY_PROBLEM`-Ergebnis reagiert wird, liegt vollständig bei der
Host-Anwendung, typischerweise:

1. Der Scan meldet einen Kandidaten als `SECURITY_PROBLEM` (alle Strategien der Kette sind
   fehlgeschlagen).
2. Die Host-Anwendung zeigt einen eigenen Prompt/Dialog für den Benutzer, z. B. "Die Prüfsumme von
   Plugin X hat sich geändert - trotzdem zulassen?".
3. Stimmt der Benutzer zu, lädt die Host-Anwendung das Plugin explizit per
   `PluginManager.forceLoad(pluginId)` (siehe [PluginManager](plugin-manager.md)), unabhängig vom
   fehlgeschlagenen Sicherheitsergebnis.
4. Optional macht der Host diese Entscheidung dauerhaft, sodass nachfolgende Scans von selbst
   erfolgreich sind, ohne eine erneute Genehmigung zu erfordern - siehe die nächsten beiden
   Abschnitte.

Nichts davon blockiert den Scan-Pfad: das Auflösen einer erwarteten Prüfsumme, das Persistieren
einer genehmigten sowie das Anzeigen eines Prompts sind allesamt synchrone Aufrufe, deren Timing
die Host-Anwendung selbst steuert.

## Ein Force-Load dauerhaft machen: `PersistableSecurityStrategy`

Eine Strategie, die ihren eigenen akzeptierten Zustand persistieren kann, kann zusätzlich zu
`PluginSecurityStrategy` `PersistableSecurityStrategy` implementieren:

```kotlin
interface PersistableSecurityStrategy : PluginSecurityStrategy {
    fun persist(pluginId: String, result: PluginScanResult)
}
```

`ChecksumSecurityStrategy` implementiert diese: `persist` berechnet die tatsächliche Prüfsumme des
Kandidaten und schreibt sie als neue erwartete Prüfsumme. Ein Host muss weder den
Persistenzschlüssel der Strategie noch die Berechnung ihres Werts selbst kennen -
`PluginManager.write` sucht die konfigurierte Strategieinstanz des angegebenen Typs für das Plugin
und delegiert an sie:

```kotlin
manager.forceLoad(pluginId)
manager.write<ChecksumSecurityStrategy>(pluginId) // persistiert die tatsächliche Prüfsumme als neue erwartete
```

Ein späteres `reload`/`scan` ist dann über die reguläre Kette erfolgreich, ohne einen erneuten
Force-Load zu erfordern. `write<T>` wirft, wenn die effektive Kette des Plugins keine Strategie vom
Typ `T` enthält.

## Ein Force-Load dauerhaft machen: die generische Sicherheitsausnahme

Nicht jede Strategie hat sinnvollen Zustand zu persistieren (z. B. eine Signaturstrategie - es gibt
keine "neue erwartete Signatur", die aufgezeichnet werden könnte). Für diese akzeptiert `forceLoad`
stattdessen ein `persistException`-Flag:

```kotlin
manager.forceLoad(pluginId, persistException = true)
```

Dies persistiert eine generische, dauerhafte Ausnahme (`PluginSecurity.SECURITY_EXCEPTION_KEY`) für
die Plugin-ID. `PluginSecurity.evaluate` prüft dieses Flag, **bevor** die Kette überhaupt
ausgewertet wird - ist es gesetzt, wird die gesamte Kette übersprungen und die Prüfung ist
erfolgreich, wobei jedes Mal, wenn dies geschieht, eine WARN protokolliert wird (da dies jede
konfigurierte Strategie, einschließlich eigener, stillschweigend umgeht). Der Host ist selbst dafür
verantwortlich, den persistierten Schlüssel aufzuheben, falls die Ausnahme jemals widerrufen werden
soll.

Bevorzugen Sie `write<T>` gegenüber `persistException`, wann immer die Strategie eine
`PersistableSecurityStrategy` ist - es zeichnet den tatsächlich akzeptierten Zustand der Strategie
auf, statt bedingungslos jede zukünftige Prüfung für dieses Plugin zu überspringen.

## Eine eigene Strategie hinzufügen

Jede Klasse, die `PluginSecurityStrategy` implementiert, kann einer Kette hinzugefügt werden, ohne
Framework-Code zu ändern:

```kotlin
class MyCustomSecurityStrategy : PluginSecurityStrategy {
    override fun check(result: PluginScanResult): PluginSecurityCheckResult =
        if (myOwnRules.allow(result)) PluginSecurityCheckResult.Success
        else PluginSecurityCheckResult.Failure("rejected by myOwnRules")
}
```
