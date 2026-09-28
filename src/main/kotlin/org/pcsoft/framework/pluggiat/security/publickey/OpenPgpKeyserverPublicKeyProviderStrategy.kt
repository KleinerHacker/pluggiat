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
 * The keyserver is treated as an *untrusted* source of key material: it is asked for a specific key
 * id, and what it returns is bound back to that key id before it is used. Every key in the response
 * is matched against the requested id by fingerprint and by 64-bit key id (suffix comparison, so a
 * short id, a long id and a full fingerprint all work), and a response that contains no matching key
 * resolves to `null` instead of to "the first key that came back". Without that binding, a keyserver
 * - or anything able to answer in its place - could hand out an attacker's key for any plugin and
 * every signature check would then verify against it. [keyserverBaseUrl] must be `https://` for the
 * same reason: over plain HTTP the response is trivially replaceable in transit. The single exception
 * is a loopback address, where there is no transit to intercept.
 *
 * @property keyIdResolver maps a plugin id to the OpenPGP key id/fingerprint (hex, with or without a
 * leading `0x`) expected to hold its public key; a `null` result means no key id is configured for
 * that plugin
 * @property keyserverBaseUrl base URL of the HKP-compatible keyserver, without a trailing slash; must
 * use `https://`, unless it addresses a loopback host
 * @property timeout connect/request timeout applied to the keyserver lookup
 * @property cacheDuration how long a resolved (or failed) lookup is cached for, avoiding a keyserver
 * round-trip on every check; a failed lookup is cached as well, so a permanently unreachable
 * keyserver does not repeatedly block the scan path
 *
 * A resolution failure (unconfigured key id, unreachable keyserver, unknown key id, key id mismatch,
 * unparsable key material) never throws - it resolves to `null`, logged as a WARN, matching
 * [PublicKeyProviderStrategy]'s defined non-fatal failure contract.
 *
 * @throws IllegalArgumentException if [keyserverBaseUrl] uses plain HTTP to a non-loopback host
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

    init {
        // Rejected at construction time, not at lookup time: a plain-HTTP keyserver makes every
        // signature check in the chain meaningless, and that must not depend on a plugin being scanned.
        require(isTransportAcceptable(keyserverBaseUrl)) {
            "keyserverBaseUrl must use https:// (or address a loopback host) - a plaintext keyserver " +
                "lookup lets anyone on the network substitute the public key a plugin's signature is " +
                "verified against, was: '$keyserverBaseUrl'"
        }
    }

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

    /**
     * Fetches the key material for [keyId] and returns the key that actually *is* [keyId], or `null`
     * if the response contains no such key. Every key ring in the response is searched, not just the
     * first one: an HKP response may legitimately carry several rings, and picking the first while
     * asking for a specific id would make the choice the server's rather than the host's.
     */
    private fun fetchAndParse(keyId: String): PublicKey? {
        val normalizedKeyId = keyId.removePrefix("0x").removePrefix("0X")
        if (normalizedKeyId.isBlank()) {
            logger.warn("Empty OpenPGP key id requested - refusing to accept an arbitrary key from '{}'", keyserverBaseUrl)
            return null
        }

        val uri = URI.create("$keyserverBaseUrl/pks/lookup?op=get&options=mr&search=0x$normalizedKeyId")
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        logger.trace("OpenPGP keyserver lookup for key id '{}' returned status {}", keyId, response.statusCode())
        // SECURITY: only a 200 carries key material; any other status resolves to "no key", never to a key
        // SECURITY: parsed out of an error page's body.
        if (response.statusCode() != 200) return null

        val decoderStream = PGPUtil.getDecoderStream(response.body().inputStream())
        val matching = PGPPublicKeyRingCollection(decoderStream, JcaKeyFingerprintCalculator()).keyRings.asSequence()
            .flatMap { ring -> ring.publicKeys.asSequence() }
            // SECURITY: this filter is the binding between what was asked for and what is accepted - without
            // SECURITY: it, the keyserver would decide which key a plugin's signature is verified against.
            .filter { publicKey -> matchesRequestedKeyId(publicKey, normalizedKeyId) }
            .toList()

        if (matching.isEmpty()) {
            logger.warn(
                "OpenPGP keyserver '{}' answered the lookup for key id '{}' with key material that does not contain " +
                    "that key - rejecting the response instead of trusting a different key",
                keyserverBaseUrl, keyId,
            )
            return null
        }

        // SECURITY: chosen only from the keys that matched the requested id, so this preference can never
        // SECURITY: pick a foreign key that happened to be in the same response.
        val signingKey: PGPPublicKey = matching.firstOrNull { it.isMasterKey } ?: matching.first()
        return JcaPGPKeyConverter().getPublicKey(signingKey)
    }

    /**
     * Whether [publicKey] is the key the host asked for. [normalizedKeyId] is compared as a *suffix*
     * of the key's full fingerprint and of its 64-bit key id, which is how OpenPGP ids relate to each
     * other: a short id is the tail of a long id, and a long id is the tail of a fingerprint. The
     * comparison is case-insensitive because hex spelling is a matter of taste, not of identity.
     */
    private fun matchesRequestedKeyId(publicKey: PGPPublicKey, normalizedKeyId: String): Boolean {
        val fingerprint = publicKey.fingerprint.joinToString("") { byte -> "%02X".format(byte) }
        val keyIdHex = "%016X".format(publicKey.keyID)
        return fingerprint.endsWith(normalizedKeyId, ignoreCase = true) ||
            keyIdHex.endsWith(normalizedKeyId, ignoreCase = true)
    }

    private data class CacheEntry(val key: PublicKey?, val expiresAt: Instant)

    companion object {
        /**
         * Whether [url]'s transport protects the response from being substituted on the way here: TLS
         * does, plain HTTP does not - except to a loopback host, where the response never leaves the
         * machine and there is no network position to attack from (which is also what makes a local
         * test keyserver usable). Anything that is neither `https://` nor `http://` to loopback is
         * rejected.
         */
        private fun isTransportAcceptable(url: String): Boolean {
            if (url.startsWith("https://", ignoreCase = true)) return true
            if (!url.startsWith("http://", ignoreCase = true)) return false
            val host = runCatching { URI.create(url).host }.getOrNull() ?: return false
            return host.equals("localhost", ignoreCase = true) ||
                host == "::1" || host == "[::1]" ||
                host.startsWith("127.")
        }
    }
}
