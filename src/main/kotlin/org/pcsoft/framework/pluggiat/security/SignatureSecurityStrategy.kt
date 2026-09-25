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

import org.pcsoft.framework.pluggiat.scanner.MultiJarWithOwnFolderScanStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.security.checksum.ChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.checksum.MessageDigestChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.Collections
import java.util.jar.JarFile

/**
 * A [PluginSecurityStrategy] that requires a candidate to be signed with a key resolved via the
 * injected [publicKeyProviderStrategy].
 *
 * @property checksumAlgorithm the [ChecksumAlgorithm] used for the [MultiJarWithOwnFolderScanStrategy]
 * checksum list (see below); defaults to SHA-512 via [MessageDigestChecksumAlgorithm]
 *
 * - [org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy]/[org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy]
 *   candidates: the candidate file itself (JAR or ZIP) must be signed - signing a ZIP uses the
 *   exact same JAR code-signing mechanism, since a signed JAR structurally is just a specially
 *   structured ZIP; the file extension is irrelevant to the JDK's signature verification.
 * - [MultiJarWithOwnFolderScanStrategy] candidates: the manifest JAR inside the candidate folder
 *   must be signed, and must additionally contain a `META-INF/plugin-checksums.txt` entry listing a
 *   [checksumAlgorithm] checksum per other JAR in the folder (one `<hex-digest>  <file-name>` line
 *   each, `shaXXXsum`-compatible); every listed checksum must match the actual file.
 */
class SignatureSecurityStrategy(
    private val publicKeyProviderStrategy: PublicKeyProviderStrategy,
    private val checksumAlgorithm: ChecksumAlgorithm = MessageDigestChecksumAlgorithm("SHA-512"),
) : PluginSecurityStrategy {
    private val logger = LoggerFactory.getLogger(SignatureSecurityStrategy::class.java)

    override fun check(result: PluginScanResult): PluginSecurityCheckResult {
        val manifest = requireNotNull(result.manifest) { "check() must only be called for LOADED results" }
        logger.debug("Checking signature of candidate '{}' for plugin '{}'", result.path, manifest.id)
        val expectedKey = publicKeyProviderStrategy.resolve(manifest.id)
            ?: return PluginSecurityCheckResult.Failure("No public key could be resolved for plugin '${manifest.id}'")

        val checkResult = if (result.location.scanStrategy is MultiJarWithOwnFolderScanStrategy) {
            checkManifestJarFolder(result.path, expectedKey)
        } else {
            checkSignedFile(result.path, expectedKey)
        }
        logger.debug("Signature check for candidate '{}' resulted in {}", result.path, checkResult)
        return checkResult
    }

    private fun checkSignedFile(file: Path, expectedKey: PublicKey): PluginSecurityCheckResult =
        try {
            verifyJarSignature(file, expectedKey)
        } catch (e: Exception) {
            PluginSecurityCheckResult.Failure("Signature verification of '$file' failed: ${e.message}")
        }

    private fun checkManifestJarFolder(folder: Path, expectedKey: PublicKey): PluginSecurityCheckResult {
        val jarPaths = Files.newDirectoryStream(folder, "*.jar").use { it.toList() }
        logger.trace("Found {} JAR(s) in folder '{}': {}", jarPaths.size, folder, jarPaths)
        val manifestJarPath = jarPaths.firstOrNull { jarPath ->
            JarFile(jarPath.toFile()).use {
                it.getJarEntry("META-INF/plugin.yml") != null || it.getJarEntry("META-INF/plugin.yaml") != null
            }
        } ?: return PluginSecurityCheckResult.Failure("No manifest JAR found in '$folder'")
        logger.trace("Identified manifest JAR '{}' in folder '{}'", manifestJarPath, folder)

        val signatureResult = checkSignedFile(manifestJarPath, expectedKey)
        if (signatureResult is PluginSecurityCheckResult.Failure) return signatureResult

        val checksumEntries = try {
            readChecksumList(manifestJarPath)
                ?: return PluginSecurityCheckResult.Failure(
                    "Manifest JAR '$manifestJarPath' contains no 'META-INF/plugin-checksums.txt' entry",
                )
        } catch (e: Exception) {
            return PluginSecurityCheckResult.Failure("Could not read checksum list from '$manifestJarPath': ${e.message}")
        }
        logger.trace("Read checksum list from '{}': {}", manifestJarPath, checksumEntries)

        for (jarPath in jarPaths.filter { it != manifestJarPath }) {
            val fileName = jarPath.fileName.toString()
            val expectedDigest = checksumEntries[fileName]
                ?: return PluginSecurityCheckResult.Failure("No checksum listed for '$fileName' in '$manifestJarPath'")
            val actualDigest = checksumAlgorithm.digest(Files.readAllBytes(jarPath))
            logger.trace("Checksum of sibling JAR '{}': expected={}, actual={}", fileName, expectedDigest, actualDigest)
            if (!expectedDigest.equals(actualDigest, ignoreCase = true)) {
                return PluginSecurityCheckResult.Failure(
                    "${checksumAlgorithm.id} checksum mismatch for '$fileName': expected $expectedDigest, was $actualDigest",
                )
            }
        }

        return PluginSecurityCheckResult.Success
    }

    private fun readChecksumList(manifestJarPath: Path): Map<String, String>? =
        JarFile(manifestJarPath.toFile()).use { jarFile ->
            val entry = jarFile.getJarEntry("META-INF/plugin-checksums.txt") ?: return null
            jarFile.getInputStream(entry).bufferedReader().readLines()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) parts[1] to parts[0] else null
                }
                .toMap()
        }

    private fun verifyJarSignature(file: Path, expectedKey: PublicKey): PluginSecurityCheckResult =
        JarFile(file.toFile(), true).use { jarFile ->
            val entries = Collections.list(jarFile.entries()).filterNot { it.isDirectory || isSigningMetadataEntry(it.name) }
            logger.trace("Verifying signature of '{}': {} signable entr{} to check", file, entries.size, if (entries.size == 1) "y" else "ies")
            if (entries.isEmpty()) return PluginSecurityCheckResult.Failure("No signable entries found in '$file'")

            for (entry in entries) {
                jarFile.getInputStream(entry).use { it.readBytes() }
                val codeSigners = entry.codeSigners
                    ?: return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$file' is not signed")
                val matches = codeSigners.any { signer -> signer.signerCertPath.certificates.firstOrNull()?.publicKey == expectedKey }
                logger.trace("Entry '{}' of '{}': {} code signer(s), matches expected key: {}", entry.name, file, codeSigners.size, matches)
                if (!matches) {
                    return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$file' is not signed with the expected public key")
                }
            }
            PluginSecurityCheckResult.Success
        }

    /**
     * Whether [entryName] is one of the JAR signing mechanism's own metadata entries
     * (`META-INF/MANIFEST.MF`, a signature file directly under `META-INF` with extension `SF`, or a
     * signature block file directly under `META-INF` with extension `RSA`, `DSA` or `EC`), which
     * are excluded from the set of entries required to carry a matching [java.security.CodeSigner]
     * themselves.
     */
    private fun isSigningMetadataEntry(entryName: String): Boolean =
        entryName == "META-INF/MANIFEST.MF" ||
            (entryName.startsWith("META-INF/") && Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)$").matches(entryName))
}
