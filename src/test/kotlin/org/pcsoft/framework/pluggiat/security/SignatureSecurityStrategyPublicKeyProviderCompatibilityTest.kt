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

package org.pcsoft.framework.pluggiat.security

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.publickey.DirectPublicKeyProviderStrategy
import org.pcsoft.framework.pluggiat.security.publickey.OpenPgpKeyserverPublicKeyProviderStrategy
import org.pcsoft.framework.pluggiat.security.publickey.OpenPgpTestFixtures
import org.pcsoft.framework.pluggiat.security.publickey.TrustStorePublicKeyProviderStrategy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

/**
 * Verifies that [SignatureSecurityStrategy] accepts a validly signed candidate regardless of which
 * shipped [org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy] resolves the
 * expected public key (IP-08 task 6).
 */
class SignatureSecurityStrategyPublicKeyProviderCompatibilityTest {
    private val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

    /**
     * Use case: SignatureSecurityStrategy accepts a signed candidate when its public key comes from
     * TrustStorePublicKeyProviderStrategy.
     */
    @Test
    fun `accepts a signed candidate using the truststore provider`(@TempDir tempDir: Path) {
        val (result, keystorePath) = signedCandidate(tempDir)
        val keyStore = KeyStore.getInstance("JKS")
        Files.newInputStream(keystorePath).use { keyStore.load(it, "changeit".toCharArray()) }
        val provider = TrustStorePublicKeyProviderStrategy(keyStore, aliasResolver = { "signer" })

        assertEquals(PluginSecurityCheckResult.Success, SignatureSecurityStrategy(provider).check(result))
    }

    /**
     * Use case: SignatureSecurityStrategy accepts a signed candidate when its public key comes from
     * DirectPublicKeyProviderStrategy.
     */
    @Test
    fun `accepts a signed candidate using the direct provider`(@TempDir tempDir: Path) {
        val (result, keystorePath) = signedCandidate(tempDir)
        val provider = DirectPublicKeyProviderStrategy(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))

        assertEquals(PluginSecurityCheckResult.Success, SignatureSecurityStrategy(provider).check(result))
    }

    /**
     * Use case: SignatureSecurityStrategy accepts a signed candidate when its public key comes from
     * OpenPgpKeyserverPublicKeyProviderStrategy, resolved against a mock HKP keyserver.
     */
    @Test
    fun `accepts a signed candidate using the OpenPGP keyserver provider`(@TempDir tempDir: Path) {
        val (result, keystorePath) = signedCandidate(tempDir)
        val exportedKey = OpenPgpTestFixtures.export(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pks/lookup") { exchange ->
            exchange.sendResponseHeaders(200, exportedKey.armoredPublicKey.size.toLong())
            exchange.responseBody.use { it.write(exportedKey.armoredPublicKey) }
        }
        server.start()
        try {
            val provider = OpenPgpKeyserverPublicKeyProviderStrategy(
                keyIdResolver = { exportedKey.keyId },
                keyserverBaseUrl = "http://127.0.0.1:${server.address.port}",
            )

            assertEquals(PluginSecurityCheckResult.Success, SignatureSecurityStrategy(provider).check(result))
        } finally {
            server.stop(0)
        }
    }

    private fun signedCandidate(tempDir: Path): Pair<PluginScanResult, Path> {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        return PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED) to keystorePath
    }
}
