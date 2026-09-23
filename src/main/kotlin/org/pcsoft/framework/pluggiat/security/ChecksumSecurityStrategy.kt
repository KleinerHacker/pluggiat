package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.security.checksum.ChecksumAlgorithm
import org.pcsoft.framework.pluggiat.security.checksum.MessageDigestChecksumAlgorithm
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [PluginSecurityStrategy] that requires a candidate's actual checksum to match the value stored
 * for it under the `"checksum"` key in [persistenceStrategy].
 *
 * @property persistenceStrategy resolves the expected checksum via
 * `persistenceStrategy.read(pluginId, "checksum")`; a host that force-loads a candidate despite a
 * failed check (see `org.pcsoft.framework.pluggiat.classloader.PluginLoader`) and wants that
 * decision to stick records the accepted checksum itself, via
 * `persistenceStrategy.write(pluginId, "checksum", <actual checksum>)`
 * @property algorithm the [ChecksumAlgorithm] used to compute the candidate's actual checksum;
 * defaults to SHA-512 via [MessageDigestChecksumAlgorithm]
 *
 * Both an unresolved expected checksum (`null`) and a mismatch are treated identically as a failed
 * check - there is no separate pending state in the framework; a host wanting to prompt the user
 * for approval does so entirely on its own side by reacting to the resulting
 * `org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.SECURITY_PROBLEM`.
 */
class ChecksumSecurityStrategy(
    private val persistenceStrategy: PluginPersistenceStrategy,
    private val algorithm: ChecksumAlgorithm = MessageDigestChecksumAlgorithm("SHA-512"),
) : PluginSecurityStrategy {
    private val logger = LoggerFactory.getLogger(ChecksumSecurityStrategy::class.java)

    override fun check(result: PluginScanResult): PluginSecurityCheckResult {
        val manifest = requireNotNull(result.manifest) { "check() must only be called for LOADED results" }
        logger.debug("Checking {} checksum of candidate '{}' for plugin '{}'", algorithm.id, result.path, manifest.id)
        val expectedDigest = persistenceStrategy.read(manifest.id, PERSISTENCE_KEY)
            ?: return PluginSecurityCheckResult.Failure("No expected ${algorithm.id} checksum known for plugin '${manifest.id}'")

        val actualDigest = algorithm.digest(candidateBytes(result.path))
        val checkResult = if (expectedDigest.equals(actualDigest, ignoreCase = true)) {
            PluginSecurityCheckResult.Success
        } else {
            PluginSecurityCheckResult.Failure(
                "${algorithm.id} checksum mismatch for plugin '${manifest.id}': expected $expectedDigest, was $actualDigest",
            )
        }
        logger.debug("Checksum check for candidate '{}' resulted in {}", result.path, checkResult)
        return checkResult
    }

    /**
     * The bytes covered by the checksum for [path]. For a `MULTI_JAR_WITH_OWN_FOLDER` candidate,
     * [path] is the candidate's folder rather than a single file; in that case, the checksum covers
     * the concatenated bytes of every `*.jar` file directly inside it, in deterministic (sorted)
     * file name order.
     */
    private fun candidateBytes(path: Path): ByteArray {
        val files = if (Files.isDirectory(path)) {
            Files.newDirectoryStream(path, "*.jar").use { it.toList() }.sortedBy { it.fileName.toString() }
        } else {
            listOf(path)
        }
        logger.trace("Computing {} checksum of candidate '{}' over {} file(s): {}", algorithm.id, path, files.size, files)
        return files.fold(ByteArray(0)) { acc, file -> acc + Files.readAllBytes(file) }
    }

    companion object {
        /** The [PluginPersistenceStrategy] key this strategy reads the expected checksum from. */
        const val PERSISTENCE_KEY: String = "checksum"
    }
}
