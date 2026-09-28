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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy
import org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SignatureSecurityStrategyTest {
    private val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

    private fun providerFor(key: PublicKey) = PublicKeyProviderStrategy { key }

    /**
     * Use case: a SINGLE_JAR candidate signed with the expected key passes the check.
     */
    @Test
    fun `accepts a SINGLE_JAR candidate signed with the expected key`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }

    /**
     * Use case: an entry added to a JAR *after* it was signed fails the check. The inserted entry carries
     * no code signer of its own, and every non-signing-metadata entry has to - otherwise a signed JAR
     * could be used as an envelope for arbitrary unsigned classes.
     */
    @Test
    fun `rejects a candidate with an entry inserted after signing`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, keystorePath, "signer")
        appendEntry(jarPath, "com/example/Injected.class", "injected after signing")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
        assertTrue((checkResult as PluginSecurityCheckResult.Failure).reason.contains("Injected.class"))
    }

    /**
     * Use case: an entry that merely *looks* like signing metadata (`META-INF/<name>.SF`) but has no
     * matching signature block is not exempt from the signing requirement - only the signature files the
     * verifier actually consumes are, so this envelope trick fails as well.
     */
    @Test
    fun `rejects a candidate with an unpaired signature-file-looking entry`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, keystorePath, "signer")
        appendEntry(jarPath, "META-INF/SMUGGLED.SF", "not a real signature file")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
        assertTrue((checkResult as PluginSecurityCheckResult.Failure).reason.contains("SMUGGLED.SF"))
    }

    /**
     * Rewrites [jarPath] with [content] added under [entryName], leaving every existing entry untouched -
     * how a candidate would be tampered with after its signature was produced.
     */
    private fun appendEntry(jarPath: Path, entryName: String, content: String) {
        val original = Files.readAllBytes(jarPath)
        ZipOutputStream(Files.newOutputStream(jarPath)).use { out ->
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(original)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(input.readBytes())
                    out.closeEntry()
                    entry = input.nextEntry
                }
            }
            out.putNextEntry(ZipEntry(entryName))
            out.write(content.toByteArray())
            out.closeEntry()
        }
    }

    /**
     * Use case: an unsigned SINGLE_JAR candidate fails the check.
     */
    @Test
    fun `rejects an unsigned SINGLE_JAR candidate`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: a SINGLE_JAR candidate signed with a different key than the one the public-key
     * provider resolves fails the check.
     */
    @Test
    fun `rejects a SINGLE_JAR candidate signed with an unexpected key`(@TempDir tempDir: Path) {
        val signerKeystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val otherKeystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "other")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, signerKeystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(otherKeystorePath, "other"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: a ZIP_JAR candidate is verified using the very same JAR signature mechanism applied
     * directly to the `.zip` file, since a signed JAR structurally is just a specially structured
     * ZIP - the file extension is irrelevant to signature verification.
     */
    @Test
    fun `accepts a ZIP_JAR candidate whose zip file itself is signed with the expected key`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val zipPath = tempDir.resolve("plugin-a.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zipStream ->
            zipStream.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zipStream.write("Manifest-Version: 1.0\n".toByteArray())
            zipStream.closeEntry()
            zipStream.putNextEntry(ZipEntry("plugin.txt"))
            zipStream.write("payload".toByteArray())
            zipStream.closeEntry()
        }
        SignatureTestFixtures.signJar(zipPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, ZipJarScanStrategy())
        val result = PluginScanResult(location, zipPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }

    /**
     * Use case: a SINGLE_JAR candidate signed with a key whose certificate has already expired is
     * rejected, even though the public key itself matches - closing the gap where an expired
     * certificate used to be accepted exactly like a valid one.
     */
    @Test
    fun `rejects a SINGLE_JAR candidate signed with an expired certificate`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateExpiredSelfSignedKeystore(tempDir, "signer")
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        SignatureTestFixtures.signJar(jarPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: a candidate is rejected outright when the public-key provider cannot resolve any
     * key for its plugin id.
     */
    @Test
    fun `rejects a candidate when no public key can be resolved`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val result = PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy({ null }).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
    }
}
