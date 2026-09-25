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

import org.pcsoft.framework.pluggiat.classloader.jar.resolveJarEntries
import org.pcsoft.framework.pluggiat.scanner.MultiJarWithOwnFolderScanStrategy
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContentReader
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.security.checksum.ChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.checksum.MessageDigestChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.checksum.digestsEqual
import org.pcsoft.framework.pluggiat.security.publickey.PublicKeyProviderStrategy
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.jar.JarInputStream

/**
 * A [PluginSecurityStrategy] that requires a candidate to be signed with a key resolved via the
 * injected [publicKeyProviderStrategy], and that signing certificate to currently be within its
 * validity period.
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
 *
 * The [check] overload taking a [PinnedPluginContent] performs the actual cryptographic signature
 * verification over those exact pinned bytes (via [JarInputStream]), and uses the shared
 * [resolveJarEntries] function - also used to load a pinned candidate, see
 * `org.pcsoft.framework.pluggiat.classloader.PinnedPluginClassLoader` - to locate the manifest JAR
 * and its checksum list within a [MultiJarWithOwnFolderScanStrategy] candidate, so both sides can
 * never diverge on which entry among duplicate ZIP entry names is authoritative.
 */
class SignatureSecurityStrategy(
    private val publicKeyProviderStrategy: PublicKeyProviderStrategy,
    private val checksumAlgorithm: ChecksumAlgorithm = MessageDigestChecksumAlgorithm("SHA-512"),
) : PluginSecurityStrategy {
    private val logger = LoggerFactory.getLogger(SignatureSecurityStrategy::class.java)

    override fun check(result: PluginScanResult): PluginSecurityCheckResult {
        requireNotNull(result.manifest) { "check() must only be called for LOADED results" }
        return check(result, PinnedPluginContentReader.read(result.path))
    }

    override fun check(result: PluginScanResult, pinnedContent: PinnedPluginContent): PluginSecurityCheckResult {
        val manifest = requireNotNull(result.manifest) { "check() must only be called for LOADED results" }
        logger.debug("Checking signature of candidate '{}' for plugin '{}'", result.path, manifest.id)
        val expectedKey = publicKeyProviderStrategy.resolve(manifest.id)
            ?: return PluginSecurityCheckResult.Failure("No public key could be resolved for plugin '${manifest.id}'")

        val checkResult = if (result.location.scanStrategy is MultiJarWithOwnFolderScanStrategy) {
            val multi = pinnedContent as? PinnedPluginContent.Multi
                ?: return PluginSecurityCheckResult.Failure("Expected multi-file pinned content for '${result.path}'")
            checkManifestJarFolder(result.path, multi, expectedKey)
        } else {
            val single = pinnedContent as? PinnedPluginContent.Single
                ?: return PluginSecurityCheckResult.Failure("Expected single-file pinned content for '${result.path}'")
            checkSignedBytes(single.bytes, result.path.toString(), expectedKey)
        }
        logger.debug("Signature check for candidate '{}' resulted in {}", result.path, checkResult)
        return checkResult
    }

    private fun checkSignedBytes(bytes: ByteArray, label: String, expectedKey: PublicKey): PluginSecurityCheckResult =
        try {
            verifyJarSignature(bytes, label, expectedKey)
        } catch (e: Exception) {
            PluginSecurityCheckResult.Failure("Signature verification of '$label' failed: ${e.message}")
        }

    private fun checkManifestJarFolder(folder: java.nio.file.Path, multi: PinnedPluginContent.Multi, expectedKey: PublicKey): PluginSecurityCheckResult {
        logger.trace("Found {} JAR(s) in folder '{}': {}", multi.filesByName.size, folder, multi.filesByName.keys)
        val manifestEntry = multi.filesByName.entries.firstOrNull { (_, bytes) ->
            val entries = resolveJarEntries(PinnedPluginContent.Single(bytes))
            entries.containsKey("META-INF/plugin.yml") || entries.containsKey("META-INF/plugin.yaml")
        } ?: return PluginSecurityCheckResult.Failure("No manifest JAR found in '$folder'")
        val (manifestFileName, manifestBytes) = manifestEntry
        val manifestLabel = "$folder/$manifestFileName"
        logger.trace("Identified manifest JAR '{}' in folder '{}'", manifestFileName, folder)

        val signatureResult = checkSignedBytes(manifestBytes, manifestLabel, expectedKey)
        if (signatureResult is PluginSecurityCheckResult.Failure) return signatureResult

        val manifestEntries = resolveJarEntries(PinnedPluginContent.Single(manifestBytes))
        val checksumListBytes = manifestEntries["META-INF/plugin-checksums.txt"]
            ?: return PluginSecurityCheckResult.Failure("Manifest JAR '$manifestLabel' contains no 'META-INF/plugin-checksums.txt' entry")
        val checksumEntries = parseChecksumList(checksumListBytes)
        logger.trace("Read checksum list from '{}': {}", manifestLabel, checksumEntries)

        for ((fileName, bytes) in multi.filesByName) {
            if (fileName == manifestFileName) continue
            val expectedDigest = checksumEntries[fileName]
                ?: return PluginSecurityCheckResult.Failure("No checksum listed for '$fileName' in '$manifestLabel'")
            val actualDigest = checksumAlgorithm.digest(bytes)
            logger.trace("Checksum of sibling JAR '{}': expected={}, actual={}", fileName, expectedDigest, actualDigest)
            if (!digestsEqual(expectedDigest, actualDigest)) {
                return PluginSecurityCheckResult.Failure(
                    "${checksumAlgorithm.id} checksum mismatch for '$fileName': expected $expectedDigest, was $actualDigest",
                )
            }
        }

        return PluginSecurityCheckResult.Success
    }

    private fun parseChecksumList(bytes: ByteArray): Map<String, String> =
        bytes.toString(Charsets.UTF_8).lines()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[1] to parts[0] else null
            }
            .toMap()

    /**
     * Verifies the JAR/ZIP signature of [bytes] against [expectedKey], reading them via
     * [JarInputStream] so no second, potentially divergent copy of the file is read from disk - the
     * pinned equivalent of the previous `JarFile`-based verification. Also enforces that the
     * matching signer's certificate currently satisfies [X509Certificate.checkValidity].
     */
    private fun verifyJarSignature(bytes: ByteArray, label: String, expectedKey: PublicKey): PluginSecurityCheckResult {
        var signableEntryCount = 0
        JarInputStream(ByteArrayInputStream(bytes), true).use { jarStream ->
            var entry = jarStream.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory && !isSigningMetadataEntry(entry.name)) {
                    signableEntryCount++
                    jarStream.readBytes() // fully read so the entry's code signers get populated

                    val codeSigners = entry.codeSigners
                        ?: return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$label' is not signed")
                    val matchingSigner = codeSigners.firstOrNull { signer -> signer.signerCertPath.certificates.firstOrNull()?.publicKey == expectedKey }
                        ?: return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$label' is not signed with the expected public key")

                    val certificate = matchingSigner.signerCertPath.certificates.firstOrNull() as? X509Certificate
                    if (certificate != null) {
                        try {
                            certificate.checkValidity()
                        } catch (e: CertificateExpiredException) {
                            return PluginSecurityCheckResult.Failure("Signing certificate for entry '${entry.name}' of '$label' has expired: ${e.message}")
                        } catch (e: CertificateNotYetValidException) {
                            return PluginSecurityCheckResult.Failure("Signing certificate for entry '${entry.name}' of '$label' is not yet valid: ${e.message}")
                        }
                    }
                }
                entry = jarStream.nextJarEntry
            }
        }
        logger.trace("Verified signature of '{}': {} signable entr{} checked", label, signableEntryCount, if (signableEntryCount == 1) "y" else "ies")
        if (signableEntryCount == 0) return PluginSecurityCheckResult.Failure("No signable entries found in '$label'")
        return PluginSecurityCheckResult.Success
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
