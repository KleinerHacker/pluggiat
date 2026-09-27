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
import java.util.zip.ZipInputStream

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
        // SECURITY: the manifest JAR is identified by its content, not by its file name - a candidate must
        // SECURITY: not be able to point the signature check at a different JAR than the one that carries the
        // SECURITY: manifest by naming it suggestively.
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
            // SECURITY: every sibling JAR must be listed. An unlisted file is rejected rather than ignored -
            // SECURITY: otherwise adding one more JAR to the folder would smuggle in unsigned, unchecked code
            // SECURITY: next to a properly signed manifest JAR.
            val expectedDigest = checksumEntries[fileName]
                ?: return PluginSecurityCheckResult.Failure("No checksum listed for '$fileName' in '$manifestLabel'")
            // SECURITY: the digest is taken over the pinned bytes, so the file that was checked is the file
            // SECURITY: that gets loaded.
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
        // Computed up front from the full entry listing: whether an entry is exempt from the signing
        // requirement depends on the *other* entries (see consumedSigningMetadataEntries).
        val signingMetadataEntries = consumedSigningMetadataEntries(bytes)
        var signableEntryCount = 0
        // SECURITY: JarInputStream(..., verify = true) is what performs the actual cryptographic verification;
        // SECURITY: reading the bytes in memory keeps it on the pinned copy instead of a second disk read.
        JarInputStream(ByteArrayInputStream(bytes), true).use { jarStream ->
            var entry = jarStream.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name !in signingMetadataEntries) {
                    signableEntryCount++
                    jarStream.readBytes() // fully read so the entry's code signers get populated

                    // SECURITY: an entry with no code signers is unsigned - present in the archive but covered
                    // SECURITY: by no signature, which is exactly how injected content would look.
                    val codeSigners = entry.codeSigners
                        ?: return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$label' is not signed")
                    // SECURITY: being signed is not enough - it has to be signed with the key the host expects
                    // SECURITY: for this plugin, otherwise any self-signed key would do.
                    val matchingSigner = codeSigners.firstOrNull { signer -> signer.signerCertPath.certificates.firstOrNull()?.publicKey == expectedKey }
                    if (matchingSigner == null) {
                        logger.trace("Entry '{}' of '{}' has {} code signer(s), none matching the expected public key", entry.name, label, codeSigners.size)
                        return PluginSecurityCheckResult.Failure("Entry '${entry.name}' of '$label' is not signed with the expected public key")
                    }
                    logger.trace("Entry '{}' of '{}' is signed with the expected public key", entry.name, label)

                    // No X.509 certificate means no validity period, no issuer and no revocation
                    // information - nothing that could be checked. Accepting such a signer (as a missing
                    // certificate silently did before) would make "signed with an expired certificate"
                    // avoidable by simply not presenting a certificate at all.
                    val certificate = matchingSigner.signerCertPath.certificates.firstOrNull() as? X509Certificate
                        ?: return PluginSecurityCheckResult.Failure(
                            "Signer of entry '${entry.name}' of '$label' presents no X.509 certificate, so its validity cannot be checked",
                        )
                    try {
                        certificate.checkValidity()
                    } catch (e: CertificateExpiredException) {
                        return PluginSecurityCheckResult.Failure("Signing certificate for entry '${entry.name}' of '$label' has expired: ${e.message}")
                    } catch (e: CertificateNotYetValidException) {
                        return PluginSecurityCheckResult.Failure("Signing certificate for entry '${entry.name}' of '$label' is not yet valid: ${e.message}")
                    }
                }
                entry = jarStream.nextJarEntry
            }
        }
        logger.trace("Verified signature of '{}': {} signable entr{} checked", label, signableEntryCount, if (signableEntryCount == 1) "y" else "ies")
        // SECURITY: an archive consisting of nothing but signing metadata proves nothing; treating it as
        // SECURITY: verified would accept an "empty" candidate as signed.
        if (signableEntryCount == 0) return PluginSecurityCheckResult.Failure("No signable entries found in '$label'")
        return PluginSecurityCheckResult.Success
    }

    /**
     * The entry names of [bytes] that belong to the JAR signing mechanism itself and are therefore not
     * required to carry a [java.security.CodeSigner] of their own: `META-INF/MANIFEST.MF` (it carries
     * the signed digests, so it cannot be signed by them) and each `META-INF/<name>.SF` signature file
     * *together with* its matching `META-INF/<name>.<RSA|DSA|EC>` signature block.
     *
     * The pairing is what makes this exact: the JDK's verifier only uses a signature file that has a
     * matching block, so those are the only ones it consumes. Exempting every entry that merely *looks*
     * like signing metadata - as a name-pattern check does - hands a candidate a way to ship an
     * unsigned file inside a signed JAR: name it `META-INF/anything.SF`, add no block for it, and it
     * would pass while being covered by no signature at all. Here, an unpaired `.SF` (or a stray block)
     * is an ordinary entry and has to be signed like any other.
     */
    private fun consumedSigningMetadataEntries(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) names += entry.name
                entry = zipStream.nextEntry
            }
        }

        val consumed = mutableSetOf<String>()
        if (JAR_MANIFEST_ENTRY_NAME in names) consumed += JAR_MANIFEST_ENTRY_NAME
        for (signatureFile in names.filter { SIGNATURE_FILE_PATTERN.matches(it) }) {
            val baseName = signatureFile.removeSuffix(SIGNATURE_FILE_SUFFIX)
            val block = SIGNATURE_BLOCK_EXTENSIONS.map { "$baseName.$it" }.firstOrNull { it in names } ?: continue
            consumed += signatureFile
            consumed += block
        }
        return consumed
    }

    private companion object {
        const val JAR_MANIFEST_ENTRY_NAME = "META-INF/MANIFEST.MF"
        const val SIGNATURE_FILE_SUFFIX = ".SF"
        val SIGNATURE_FILE_PATTERN = Regex("META-INF/[^/]+\\.SF$")
        val SIGNATURE_BLOCK_EXTENSIONS = listOf("RSA", "DSA", "EC")
    }
}
