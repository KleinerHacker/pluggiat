package org.pcsoft.framework.pluggiat.security.publickey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.security.SignatureTestFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class TrustStorePublicKeyProviderStrategyTest {

    /**
     * Use case: a plugin id resolves to the public key stored under the truststore alias of the same
     * name (the default alias resolver).
     */
    @Test
    fun `resolves the public key stored under the plugin id's alias`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val strategy = TrustStorePublicKeyProviderStrategy(loadKeystore(keystorePath))

        val resolved = strategy.resolve("signer")

        assertEquals(SignatureTestFixtures.readPublicKey(keystorePath, "signer"), resolved)
    }

    /**
     * Use case: a plugin id without a matching alias in the truststore resolves to `null` instead of
     * throwing.
     */
    @Test
    fun `resolves to null for an unknown alias`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val strategy = TrustStorePublicKeyProviderStrategy(loadKeystore(keystorePath))

        assertNull(strategy.resolve("unknown-plugin"))
    }

    /**
     * Use case: a custom alias resolver can map a plugin id to a differently named truststore alias.
     */
    @Test
    fun `resolves the public key via a custom alias resolver`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val strategy = TrustStorePublicKeyProviderStrategy(loadKeystore(keystorePath), aliasResolver = { "signer" })

        val resolved = strategy.resolve("plugin-a")

        assertEquals(SignatureTestFixtures.readPublicKey(keystorePath, "signer"), resolved)
    }

    private fun loadKeystore(path: Path): KeyStore {
        val keyStore = KeyStore.getInstance("JKS")
        Files.newInputStream(path).use { keyStore.load(it, "changeit".toCharArray()) }
        return keyStore
    }
}
