# Public-Key-Provider

`SignatureSecurityStrategy` (siehe [Sicherheit](security.md)) löst den öffentlichen Schlüssel, gegen
den sie die Signatur eines Kandidaten prüft, nicht selbst auf - sie delegiert dies an eine
injizierte `org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy`:

```kotlin
fun interface PublicKeyProviderStrategy {
    fun resolve(pluginId: String): PublicKey?
}
```

`resolve` wird anhand der Manifest-`id` des Plugins nachgeschlagen. Ein `null`-Ergebnis ist ein
definierter, nicht schwerwiegender Fehlschlag - die Signaturprüfung schlägt für diesen Kandidaten
einfach fehl, genau wie bei einer tatsächlichen Signaturabweichung - niemals eine geworfene
Ausnahme. Drei Implementierungen werden mit dem Framework ausgeliefert.

## `TrustStorePublicKeyProviderStrategy`

Löst den öffentlichen Schlüssel aus einem in einem Java-`KeyStore` (einem Truststore) gespeicherten
Zertifikat auf, nachgeschlagen unter einem über `aliasResolver` aus der Plugin-ID abgeleiteten Alias
(standardmäßig die Plugin-ID selbst):

```kotlin
val keyStore = KeyStore.getInstance("JKS").apply {
    Files.newInputStream(Path.of("my-truststore.jks")).use { load(it, "changeit".toCharArray()) }
}

// Standard: Alias == Plugin-ID
val provider = TrustStorePublicKeyProviderStrategy(keyStore)

// eigene Zuordnung, z. B. teilen sich alle Plugins eines Anbieters einen Alias
val vendorProvider = TrustStorePublicKeyProviderStrategy(keyStore, aliasResolver = { "vendor-acme" })
```

Ein unbekannter Alias oder ein Alias ohne gespeichertes Zertifikat löst zu `null` auf und
protokolliert eine WARN - die Prüfung schlägt einfach fehl, es wird keine Ausnahme geworfen.

## `DirectPublicKeyProviderStrategy`

Löst immer denselben, direkt übergebenen `java.security.PublicKey` auf, unabhängig von der
Plugin-ID - nützlich, wenn für jedes Plugin ein einziger Signierschlüssel verwendet wird:

```kotlin
val provider = DirectPublicKeyProviderStrategy(myPublicKey)
```

## `OpenPgpKeyserverPublicKeyProviderStrategy`

Löst den öffentlichen Schlüssel von einem HKP-kompatiblen OpenPGP-Keyserver (z. B.
`keys.openpgp.org`) auf, nachgeschlagen anhand einer über `keyIdResolver` aus der Plugin-ID
abgeleiteten OpenPGP-Schlüssel-ID/-Fingerabdruck:

```kotlin
val provider = OpenPgpKeyserverPublicKeyProviderStrategy(
    keyIdResolver = { pluginId -> myKeyIdRegistry[pluginId] }, // z. B. aus Ihrer eigenen Plugin-Registry
    keyserverBaseUrl = "https://keys.openpgp.org", // Standard
    timeout = Duration.ofSeconds(10), // Standard
    cacheDuration = Duration.ofHours(1), // Standard
)
```

* Gibt `keyIdResolver` für eine Plugin-ID `null` zurück, löst die gesamte Suche zu `null` auf, ohne
  den Keyserver zu kontaktieren.
* Die Suche verwendet den Standard-HKP-Endpunkt `GET /pks/lookup?op=get&options=mr&search=0x<keyId>`,
  sodass über `keyserverBaseUrl` jeder HKP-kompatible Keyserver konfiguriert werden kann, nicht nur
  `keys.openpgp.org`.
* Der signaturfähige Hauptschlüssel eines aufgelösten Schlüssels wird über die OpenPGP-Unterstützung
  von Bouncy Castle (RFC 9580) in einen `java.security.PublicKey` umgewandelt.
* `timeout` begrenzt sowohl Verbindungs- als auch Anfragezeit; ein nicht erreichbarer oder langsamer
  Keyserver löst zu `null` auf (als WARN protokolliert), statt den Scan-Pfad zu blockieren.
* `cacheDuration` begrenzt, wie lange ein Suchergebnis - erfolgreich oder fehlgeschlagen - im
  Speicher gehalten wird, bevor der Keyserver für dieselbe Schlüssel-ID erneut abgefragt wird,
  sodass ein Plugin-Scan nicht pro Scan einen erneuten Netzwerk-Roundtrip auslöst.

## Einen eigenen Provider hinzufügen

Da `PublicKeyProviderStrategy` ein funktionales Interface ist, kann eine eigene Quelle (z. B. die
eigene Plugin-Registry-API eines Hosts) als Lambda eingebunden werden, ganz ohne
Framework-Änderungen:

```kotlin
val provider = PublicKeyProviderStrategy { pluginId -> myPluginRegistry.lookupSigningKey(pluginId) }
val strategy = SignatureSecurityStrategy(provider)
```
