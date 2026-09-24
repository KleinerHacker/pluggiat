package org.pcsoft.framework.pluggiat.security.publickey

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

class DirectPublicKeyProviderStrategyTest {

    /**
     * Use case: the directly supplied public key is returned unchanged, regardless of plugin id.
     */
    @Test
    fun `resolves the directly supplied public key unchanged`() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val strategy = DirectPublicKeyProviderStrategy(publicKey)

        assertSame(publicKey, strategy.resolve("plugin-a"))
        assertSame(publicKey, strategy.resolve("plugin-b"))
    }
}
