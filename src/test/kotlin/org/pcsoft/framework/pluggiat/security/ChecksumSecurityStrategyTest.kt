package org.pcsoft.framework.pluggiat.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.checksum.MessageDigestChecksumAlgorithm
import java.nio.file.Path

class ChecksumSecurityStrategyTest {

    private fun candidate(tempDir: Path): PluginScanResult {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")
        return PluginScanResult(location, jarPath, manifest, PluginScanStatus.LOADED)
    }

    private fun persistenceOf(expectedChecksum: String?): PluginPersistenceStrategy =
        object : PluginPersistenceStrategy {
            override fun read(pluginId: String, key: String): String? = expectedChecksum
            override fun write(pluginId: String, key: String, value: String) {}
        }

    /**
     * Use case: no checksum stored under the `"checksum"` persistence key (no expected checksum
     * known yet) is treated as a failed check, not as a distinct pending state.
     */
    @Test
    fun `treats an unresolved expected checksum as a failed check`(@TempDir tempDir: Path) {
        val strategy = ChecksumSecurityStrategy(persistenceOf(null))

        val result = strategy.check(candidate(tempDir))

        assertTrue(result is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: an expected checksum that does not match the candidate's actual checksum is a
     * failed check.
     */
    @Test
    fun `treats a checksum mismatch as a failed check`(@TempDir tempDir: Path) {
        val strategy = ChecksumSecurityStrategy(persistenceOf("0".repeat(128)))

        val result = strategy.check(candidate(tempDir))

        assertTrue(result is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: without an explicit algorithm, the strategy defaults to SHA-512.
     */
    @Test
    fun `treats a matching SHA-512 checksum as a successful check by default`(@TempDir tempDir: Path) {
        val result = candidate(tempDir)
        val expected = MessageDigestChecksumAlgorithm("SHA-512").digest(result.path.toFile().readBytes())
        val strategy = ChecksumSecurityStrategy(persistenceOf(expected))

        val checkResult = strategy.check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }

    /**
     * Use case: an explicitly configured [org.pcsoft.framework.pluggiat.security.checksum.ChecksumAlgorithm]
     * (here SHA-256) is used instead of the SHA-512 default.
     */
    @Test
    fun `uses the explicitly configured checksum algorithm instead of the default`(@TempDir tempDir: Path) {
        val result = candidate(tempDir)
        val algorithm = MessageDigestChecksumAlgorithm("SHA-256")
        val expected = algorithm.digest(result.path.toFile().readBytes())
        val strategy = ChecksumSecurityStrategy(persistenceOf(expected), algorithm = algorithm)

        val checkResult = strategy.check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }

    /**
     * Use case: a force-loaded plugin's checksum can be recorded through the same
     * [PluginPersistenceStrategy] instance the strategy reads from, so a subsequent check succeeds.
     */
    @Test
    fun `accepts a checksum recorded via the persistence strategy's write side`(@TempDir tempDir: Path) {
        val store = mutableMapOf<String, String>()
        val persistence = object : PluginPersistenceStrategy {
            override fun read(pluginId: String, key: String): String? = store["$pluginId.$key"]
            override fun write(pluginId: String, key: String, value: String) {
                store["$pluginId.$key"] = value
            }
        }
        val result = candidate(tempDir)
        val actual = MessageDigestChecksumAlgorithm("SHA-512").digest(result.path.toFile().readBytes())
        persistence.write("plugin-a", ChecksumSecurityStrategy.PERSISTENCE_KEY, actual)

        val checkResult = ChecksumSecurityStrategy(persistence).check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }
}
