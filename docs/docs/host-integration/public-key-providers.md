# Public key providers

`SignatureSecurityStrategy` (see [Security](security.md)) does not resolve the public key it
verifies a candidate's signature against itself - it delegates that to an injected
`org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy`:

```kotlin
fun interface PublicKeyProviderStrategy {
    fun resolve(pluginId: String): PublicKey?
}
```

`resolve` is looked up by the plugin's manifest `id`. A `null` result is a defined, non-fatal
failure - the signature check simply fails for that candidate, the same as an actual signature
mismatch - never a thrown exception. Three implementations ship with the framework.

!!! tip "Security recommendations"

    * Prefer `TrustStorePublicKeyProviderStrategy` over hardcoding a key in code
      (`DirectPublicKeyProviderStrategy`) once you manage more than one signing key or expect to
      rotate one.
    * `OpenPgpKeyserverPublicKeyProviderStrategy` trusts whatever the keyserver returns for a key id -
      pin `keyIdResolver` to fingerprints you resolved and recorded yourself, not to a name-based
      search, and keep `cacheDuration` short enough to notice a revoked key in reasonable time.
    * A `null` resolution is a silent, logged failure, not an exception - make sure your own
      monitoring surfaces the resulting `SECURITY_PROBLEM`, since nothing else will page you about it.

## `TrustStorePublicKeyProviderStrategy`

Resolves the public key from a certificate stored in a Java `KeyStore` (a truststore), looked up
under an alias derived from the plugin id via `aliasResolver` (defaults to the plugin id itself):

```kotlin
val keyStore = KeyStore.getInstance("JKS").apply {
    Files.newInputStream(Path.of("my-truststore.jks")).use { load(it, "changeit".toCharArray()) }
}

// default: alias == plugin id
val provider = TrustStorePublicKeyProviderStrategy(keyStore)

// custom mapping, e.g. every plugin from one vendor shares an alias
val vendorProvider = TrustStorePublicKeyProviderStrategy(keyStore, aliasResolver = { "vendor-acme" })
```

An unknown alias, or an alias without a stored certificate, resolves to `null` and logs a WARN -
the check simply fails, no exception is thrown.

## `DirectPublicKeyProviderStrategy`

Always resolves the same, directly supplied `java.security.PublicKey`, regardless of plugin id -
useful when a single signing key is used for every plugin:

```kotlin
val provider = DirectPublicKeyProviderStrategy(myPublicKey)
```

## `OpenPgpKeyserverPublicKeyProviderStrategy`

Resolves the public key from an HKP-compatible OpenPGP keyserver (e.g. `keys.openpgp.org`),
looked up by an OpenPGP key id/fingerprint derived from the plugin id via `keyIdResolver`:

```kotlin
val provider = OpenPgpKeyserverPublicKeyProviderStrategy(
    keyIdResolver = { pluginId -> myKeyIdRegistry[pluginId] }, // e.g. from your own plugin registry
    keyserverBaseUrl = "https://keys.openpgp.org", // default
    timeout = Duration.ofSeconds(10), // default
    cacheDuration = Duration.ofHours(1), // default
)
```

* `keyIdResolver` returning `null` for a plugin id resolves the whole lookup to `null` without
  contacting the keyserver.
* The lookup uses the standard HKP `GET /pks/lookup?op=get&options=mr&search=0x<keyId>` endpoint,
  so any HKP-compatible keyserver can be configured via `keyserverBaseUrl`, not just
  `keys.openpgp.org`.
* A resolved key's signing-capable master key is converted to a `java.security.PublicKey` via
  Bouncy Castle's OpenPGP support (RFC 9580).
* `timeout` bounds both connection and request time; an unreachable or slow keyserver resolves to
  `null` (logged as a WARN) instead of blocking the scan path.
* `cacheDuration` bounds how long a lookup result - successful or failed - is kept in memory before
  the keyserver is queried again for the same key id, so a plugin scan does not repeat a network
  round-trip per scan.

## Adding a custom provider

Since `PublicKeyProviderStrategy` is a functional interface, a custom source (e.g. a host's own
plugin registry API) can be plugged in as a lambda, without any framework changes:

```kotlin
val provider = PublicKeyProviderStrategy { pluginId -> myPluginRegistry.lookupSigningKey(pluginId) }
val strategy = SignatureSecurityStrategy(provider)
```
