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
import org.pcsoft.framework.pluggiat.scanner.MultiJarWithOwnFolderScanStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy
import org.pcsoft.framework.pluggiat.security.checksum.MessageDigestChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SignatureSecurityStrategyTest {
    private val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")
    private val checksumAlgorithm = MessageDigestChecksumAlgorithm("SHA-512")

    private fun providerFor(key: PublicKey) = PublicKeyProviderStrategy { key }

    private fun sha512Hex(path: Path): String = checksumAlgorithm.digest(Files.readAllBytes(path))

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
     * Use case: a MULTI_JAR_WITH_OWN_FOLDER candidate whose manifest JAR is signed and carries a
     * matching checksum list for the other JARs in the folder passes the check.
     */
    @Test
    fun `accepts a MULTI_JAR_WITH_OWN_FOLDER candidate with signed manifest JAR and matching checksum list`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val folder = Files.createDirectory(tempDir.resolve("plugin-a"))
        val libJarPath = folder.resolve("lib.jar")
        PluginScannerTestFixtures.writeJar(libJarPath, mapOf("some/Class.class" to "not real bytecode"))
        val checksumListEntry = "${sha512Hex(libJarPath)}  lib.jar\n"
        val manifestJarPath = folder.resolve("plugin-a-manifest.jar")
        PluginScannerTestFixtures.writeJar(
            manifestJarPath,
            mapOf(
                "META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a"),
                "META-INF/plugin-checksums.txt" to checksumListEntry,
            ),
        )
        SignatureTestFixtures.signJar(manifestJarPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, MultiJarWithOwnFolderScanStrategy())
        val result = PluginScanResult(location, folder, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }

    /**
     * Use case: a MULTI_JAR_WITH_OWN_FOLDER candidate whose checksum list no longer matches one of
     * the other JARs (tampered after signing) fails the check.
     */
    @Test
    fun `rejects a MULTI_JAR_WITH_OWN_FOLDER candidate with a checksum mismatch`(@TempDir tempDir: Path) {
        val keystorePath = SignatureTestFixtures.generateSelfSignedKeystore(tempDir, "signer")
        val folder = Files.createDirectory(tempDir.resolve("plugin-a"))
        val libJarPath = folder.resolve("lib.jar")
        PluginScannerTestFixtures.writeJar(libJarPath, mapOf("some/Class.class" to "not real bytecode"))
        val wrongChecksumListEntry = "${"0".repeat(128)}  lib.jar\n"
        val manifestJarPath = folder.resolve("plugin-a-manifest.jar")
        PluginScannerTestFixtures.writeJar(
            manifestJarPath,
            mapOf(
                "META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a"),
                "META-INF/plugin-checksums.txt" to wrongChecksumListEntry,
            ),
        )
        SignatureTestFixtures.signJar(manifestJarPath, keystorePath, "signer")
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, MultiJarWithOwnFolderScanStrategy())
        val result = PluginScanResult(location, folder, manifest, PluginScanStatus.LOADED)

        val checkResult = SignatureSecurityStrategy(providerFor(SignatureTestFixtures.readPublicKey(keystorePath, "signer"))).check(result)

        assertTrue(checkResult is PluginSecurityCheckResult.Failure)
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
