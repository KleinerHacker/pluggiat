/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

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
