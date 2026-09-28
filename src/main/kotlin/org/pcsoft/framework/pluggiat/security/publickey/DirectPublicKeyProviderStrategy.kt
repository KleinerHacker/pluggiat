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
import java.security.PublicKey

/**
 * A [PublicKeyProviderStrategy] that always resolves the same, directly supplied [publicKey],
 * regardless of the plugin id - useful when a single signing key is used for every plugin.
 */
class DirectPublicKeyProviderStrategy(private val publicKey: PublicKey) : PublicKeyProviderStrategy {
    private val logger = LoggerFactory.getLogger(DirectPublicKeyProviderStrategy::class.java)

    // SECURITY: the key is fixed at construction by the host, independent of the plugin id - the strongest
    // SECURITY: binding of the three providers, since nothing about the candidate can influence it.
    override fun resolve(pluginId: String): PublicKey {
        logger.trace("Resolving fixed, host-supplied public key for plugin '{}' via DirectPublicKeyProviderStrategy", pluginId)
        return publicKey
    }
}
