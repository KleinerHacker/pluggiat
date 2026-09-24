package org.pcsoft.framework.pluggiat.security.publickey

import java.security.PublicKey

/**
 * A [PublicKeyProviderStrategy] that always resolves the same, directly supplied [publicKey],
 * regardless of the plugin id - useful when a single signing key is used for every plugin.
 */
class DirectPublicKeyProviderStrategy(private val publicKey: PublicKey) : PublicKeyProviderStrategy {
    override fun resolve(pluginId: String): PublicKey = publicKey
}
