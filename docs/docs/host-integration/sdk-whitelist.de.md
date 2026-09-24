# SDK-Whitelist

Jedes Plugin wird in seinen eigenen isolierten `PluginClassLoader` geladen, einen
Parent-Last-Classloader, der standardmäßig keine host-internen Klassen sehen kann. `PluginLoader`
(siehe [Classloader-Isolation](../plugin-development/dependencies.md)) öffnet gezielt genau die
Teile des eigenen SDK des Hosts, die Plugins nutzen sollen, über eine Liste von
`SdkWhitelistEntry`:

```kotlin
data class SdkWhitelistEntry(
    val packageName: String,
    val recursive: Boolean = true,
)

val loader = PluginLoader(
    sdkWhitelist = listOf(
        SdkWhitelistEntry("com.myhost.pluginapi"),
        SdkWhitelistEntry("com.myhost.pluginapi.legacy", recursive = false),
    ),
)
```

## Was auf die Whitelist gehört

Nur die eigene, plugin-zugewandte API des Hosts - typischerweise das/die Interface(s), das/die
Plugin-Implementierungen implementieren müssen (das host-definierte `T`, auf das
`ExtensionConfiguration<T>` eines Plugins auflöst), sowie gemeinsame DTOs oder Utility-Klassen, die
der Host Plugins ausdrücklich direkt zur Nutzung anbieten möchte. Die Whitelist wird niemals vom
Framework selbst abgeleitet; es liegt vollständig beim Host, zu entscheiden, was seine eigene
SDK-Oberfläche ist.

Interne Host-Implementierungspakete sollten niemals auf die Whitelist gesetzt werden - alles, was
hier nicht aufgeführt ist, bleibt für Plugin-Code vollständig unerreichbar, selbst per Reflection.

## Was nicht auf die Whitelist muss

JDK-Plattformklassen (`java.*`, `javax.*`) sind über den Classloader eines Plugins immer auflösbar,
unabhängig von der Whitelist - Plugin-Bytecode referenziert unbedingt zentrale JDK-Typen (angefangen
bei `java.lang.Object`), sodass diese bedingungslos an den eigenen Plattform-Classloader der JDK
delegiert werden, noch bevor die Whitelist überhaupt konsultiert wird.

## Abgleich

* `recursive = true` (der Standard): `packageName` und alle seine Unterpakete, in beliebiger Tiefe,
  werden zugänglich gemacht.
* `recursive = false`: nur Klassen direkt in `packageName` werden zugänglich gemacht; Unterpakete
  bleiben unsichtbar.

## Force-Load

`PluginLoader.load` führt keine eigene Sicherheitsprüfung durch und ist bedingungslos aufrufbar,
unabhängig vom Ergebnis einer vorherigen Sicherheitsprüfung - es gibt keinen separaten
"Force"-Parameter oder Codepfad. Eine Host-Anwendung, die entscheidet, ein Plugin trotz eines
`SECURITY_PROBLEM`-Scanergebnisses zu laden, ruft `load` einfach wie jedes andere Plugin auf:

```kotlin
val result = loader.load(path, manifest)
```

Zu entscheiden, *ob* dies gerechtfertigt ist, und diese Entscheidung zu protokollieren (z. B.
Plugin-ID und ursprünglicher Fehlschlaggrund), liegt vollständig in der Verantwortung der
Host-Anwendung selbst - `PluginLoader` trifft diese Entscheidung weder noch gibt er selbst einen
Protokolleintrag darüber aus (siehe
[Host-Freigabeablauf nach einem `SECURITY_PROBLEM`](security.md#host-freigabeablauf-nach-einem-security_problem)).
