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

import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContentReader
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
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
 * The candidate file itself (a JAR for [org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy], a
 * ZIP for [org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy]) must be signed - signing a ZIP uses
 * the exact same JAR code-signing mechanism, since a signed JAR structurally is just a specially
 * structured ZIP; the file extension is irrelevant to the JDK's signature verification.
 *
 * The [check] overload taking a [PinnedPluginContent] performs the actual cryptographic signature
 * verification over those exact pinned bytes (via [JarInputStream]).
 */
class SignatureSecurityStrategy(
    private val publicKeyProviderStrategy: PublicKeyProviderStrategy,
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

        val bytes = when (pinnedContent) {
            is PinnedPluginContent.Single -> pinnedContent.bytes
        }
        val checkResult = checkSignedBytes(bytes, result.path.toString(), expectedKey)
        logger.debug("Signature check for candidate '{}' resulted in {}", result.path, checkResult)
        return checkResult
    }

    private fun checkSignedBytes(bytes: ByteArray, label: String, expectedKey: PublicKey): PluginSecurityCheckResult =
        try {
            verifyJarSignature(bytes, label, expectedKey)
        } catch (e: Exception) {
            PluginSecurityCheckResult.Failure("Signature verification of '$label' failed: ${e.message}")
        }

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
