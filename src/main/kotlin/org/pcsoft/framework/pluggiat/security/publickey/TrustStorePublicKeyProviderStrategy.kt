package org.pcsoft.framework.pluggiat.security.publickey

import org.slf4j.LoggerFactory
import java.security.KeyStore
import java.security.PublicKey

/**
 * A [PublicKeyProviderStrategy] that resolves a plugin's expected public key from a Java [KeyStore]
 * (a truststore), looking up the certificate under an alias derived from the plugin id via
 * [aliasResolver].
 *
 * @property keyStore the already loaded truststore to resolve public keys from
 * @property aliasResolver maps a plugin id to the truststore alias expected to hold its public key
 * certificate; defaults to using the plugin id itself as the alias
 */
class TrustStorePublicKeyProviderStrategy(
    private val keyStore: KeyStore,
    private val aliasResolver: (String) -> String = { it },
) : PublicKeyProviderStrategy {
    private val logger = LoggerFactory.getLogger(TrustStorePublicKeyProviderStrategy::class.java)

    override fun resolve(pluginId: String): PublicKey? {
        val alias = aliasResolver(pluginId)
        val key = try {
            keyStore.getCertificate(alias)?.publicKey
        } catch (e: Exception) {
            logger.warn("Could not resolve public key for plugin '{}' from truststore alias '{}': {}", pluginId, alias, e.message)
            return null
        }
        if (key == null) {
            logger.warn("No truststore entry found for plugin '{}' under alias '{}'", pluginId, alias)
        }
        return key
    }
}
