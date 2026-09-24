package org.pcsoft.framework.pluggiat.security.publickey

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
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
