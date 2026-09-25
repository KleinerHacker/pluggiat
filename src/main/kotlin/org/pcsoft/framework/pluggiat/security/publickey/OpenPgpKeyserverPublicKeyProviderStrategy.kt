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

import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A [PublicKeyProviderStrategy] that resolves a plugin's expected public key from an HKP-compatible
 * OpenPGP keyserver (e.g. `keys.openpgp.org`), looked up by a key id/fingerprint derived from the
 * plugin id via [keyIdResolver].
 *
 * @property keyIdResolver maps a plugin id to the OpenPGP key id/fingerprint (hex, with or without a
 * leading `0x`) expected to hold its public key; a `null` result means no key id is configured for
 * that plugin
 * @property keyserverBaseUrl base URL of the HKP-compatible keyserver, without a trailing slash
 * @property timeout connect/request timeout applied to the keyserver lookup
 * @property cacheDuration how long a resolved (or failed) lookup is cached for, avoiding a keyserver
 * round-trip on every check; a failed lookup is cached as well, so a permanently unreachable
 * keyserver does not repeatedly block the scan path
 *
 * A resolution failure (unconfigured key id, unreachable keyserver, unknown key id, unparsable key
 * material) never throws - it resolves to `null`, logged as a WARN, matching
 * [PublicKeyProviderStrategy]'s defined non-fatal failure contract.
 */
class OpenPgpKeyserverPublicKeyProviderStrategy(
    private val keyIdResolver: (String) -> String?,
    private val keyserverBaseUrl: String = "https://keys.openpgp.org",
    private val timeout: Duration = Duration.ofSeconds(10),
    private val cacheDuration: Duration = Duration.ofHours(1),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) : PublicKeyProviderStrategy {
    private val logger = LoggerFactory.getLogger(OpenPgpKeyserverPublicKeyProviderStrategy::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun resolve(pluginId: String): PublicKey? {
        val keyId = keyIdResolver(pluginId)
        if (keyId == null) {
            logger.warn("No OpenPGP key id configured for plugin '{}'", pluginId)
            return null
        }

        val cached = cache[keyId]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            logger.debug("Using cached OpenPGP lookup for key id '{}' (plugin '{}')", keyId, pluginId)
            return cached.key
        }

        val key = try {
            fetchAndParse(keyId)
        } catch (e: Exception) {
            logger.warn("Could not resolve OpenPGP public key for plugin '{}' (key id '{}') from '{}': {}", pluginId, keyId, keyserverBaseUrl, e.message)
            null
        }
        if (key == null) {
            logger.warn("No OpenPGP public key found for plugin '{}' under key id '{}'", pluginId, keyId)
        }
        cache[keyId] = CacheEntry(key, Instant.now().plus(cacheDuration))
        return key
    }

    private fun fetchAndParse(keyId: String): PublicKey? {
        val normalizedKeyId = keyId.removePrefix("0x")
        val uri = URI.create("$keyserverBaseUrl/pks/lookup?op=get&options=mr&search=0x$normalizedKeyId")
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        logger.trace("OpenPGP keyserver lookup for key id '{}' returned status {}", keyId, response.statusCode())
        if (response.statusCode() != 200) return null

        val decoderStream = PGPUtil.getDecoderStream(response.body().inputStream())
        val keyRing = PGPPublicKeyRingCollection(decoderStream, JcaKeyFingerprintCalculator()).keyRings.asSequence().firstOrNull()
            ?: return null
        val signingKey: PGPPublicKey = keyRing.publicKeys.asSequence().firstOrNull { it.isMasterKey } ?: keyRing.publicKey
        return JcaPGPKeyConverter().getPublicKey(signingKey)
    }

    private data class CacheEntry(val key: PublicKey?, val expiresAt: Instant)
}
