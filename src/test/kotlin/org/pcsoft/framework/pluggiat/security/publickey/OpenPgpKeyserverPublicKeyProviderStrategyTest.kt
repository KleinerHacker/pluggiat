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

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

class OpenPgpKeyserverPublicKeyProviderStrategyTest {

    /**
     * Use case: a plugin id whose configured key id is present on the keyserver resolves to the
     * matching public key.
     */
    @Test
    fun `resolves the public key returned by the keyserver`() {
        val exportedKey = OpenPgpTestFixtures.generateKey()
        withKeyserver({ exchange ->
            exchange.sendResponseHeaders(200, exportedKey.armoredPublicKey.size.toLong())
            exchange.responseBody.use { it.write(exportedKey.armoredPublicKey) }
        }) { baseUrl ->
            val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { exportedKey.keyId }, keyserverBaseUrl = baseUrl)

            assertEquals(exportedKey.publicKey, strategy.resolve("plugin-a"))
        }
    }

    /**
     * Use case: a plugin id whose configured key id is not found on the keyserver resolves to `null`.
     */
    @Test
    fun `resolves to null for a key id unknown to the keyserver`() {
        withKeyserver({ exchange -> exchange.sendResponseHeaders(404, -1) }) { baseUrl ->
            val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { "DEADBEEFDEADBEEF" }, keyserverBaseUrl = baseUrl)

            assertNull(strategy.resolve("plugin-a"))
        }
    }

    /**
     * Use case: an unreachable keyserver resolves to `null` instead of throwing or blocking the scan
     * path.
     */
    @Test
    fun `resolves to null when the keyserver is unreachable`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(
            keyIdResolver = { "DEADBEEFDEADBEEF" },
            keyserverBaseUrl = "http://127.0.0.1:$closedPort",
            timeout = Duration.ofSeconds(2),
        )

        assertNull(strategy.resolve("plugin-a"))
    }

    /**
     * Use case: a plugin id without a configured key id resolves to `null` without attempting a
     * keyserver lookup.
     */
    @Test
    fun `resolves to null when no key id is configured for the plugin`() {
        val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { null }, keyserverBaseUrl = "http://127.0.0.1:1")

        assertNull(strategy.resolve("plugin-a"))
    }

    /**
     * Use case: a second lookup for the same key id within [OpenPgpKeyserverPublicKeyProviderStrategy]'s
     * cache duration is served from the cache instead of hitting the keyserver again.
     */
    @Test
    fun `caches a resolved key instead of querying the keyserver again`() {
        val exportedKey = OpenPgpTestFixtures.generateKey()
        var requestCount = 0
        withKeyserver({ exchange ->
            requestCount++
            exchange.sendResponseHeaders(200, exportedKey.armoredPublicKey.size.toLong())
            exchange.responseBody.use { it.write(exportedKey.armoredPublicKey) }
        }) { baseUrl ->
            val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(
                keyIdResolver = { exportedKey.keyId },
                keyserverBaseUrl = baseUrl,
                cacheDuration = Duration.ofMinutes(5),
            )

            strategy.resolve("plugin-a")
            strategy.resolve("plugin-a")

            assertEquals(1, requestCount)
        }
    }

    /**
     * Use case: a keyserver that answers the lookup for one key id with a *different* key resolves to
     * `null` instead of returning that key - the response is bound back to the id that was asked for, so
     * a keyserver (or anything answering in its place) cannot substitute the key a plugin's signature is
     * verified against.
     */
    @Test
    fun `rejects a keyserver response whose key id differs from the requested one`() {
        val requestedKey = OpenPgpTestFixtures.generateKey()
        val foreignKey = OpenPgpTestFixtures.generateKey()
        withKeyserver({ exchange ->
            exchange.sendResponseHeaders(200, foreignKey.armoredPublicKey.size.toLong())
            exchange.responseBody.use { it.write(foreignKey.armoredPublicKey) }
        }) { baseUrl ->
            val strategy = OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { requestedKey.keyId }, keyserverBaseUrl = baseUrl)

            assertNull(strategy.resolve("plugin-a"))
        }
    }

    /**
     * Use case: a keyserver base URL that uses plain HTTP to a non-loopback host is rejected when the
     * strategy is constructed - over plaintext, the key material is replaceable in transit, which would
     * make every signature check built on it meaningless.
     */
    @Test
    fun `rejects a plaintext keyserver base url`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { "DEADBEEFDEADBEEF" }, keyserverBaseUrl = "http://keys.example.com")
        }

        assertTrue(exception.message?.contains("https") == true)
    }

    /**
     * Use case: an `https` base URL is accepted, and so is plain HTTP to a loopback host - the latter is
     * the one case where there is no transit to intercept (and what makes a local test keyserver usable).
     */
    @Test
    fun `accepts an https base url and a loopback plaintext url`() {
        OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { null }, keyserverBaseUrl = "https://keys.openpgp.org")
        OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { null }, keyserverBaseUrl = "http://127.0.0.1:11371")
        OpenPgpKeyserverPublicKeyProviderStrategy(keyIdResolver = { null }, keyserverBaseUrl = "http://localhost:11371")
    }

    private fun withKeyserver(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, block: (baseUrl: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pks/lookup", handler)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
