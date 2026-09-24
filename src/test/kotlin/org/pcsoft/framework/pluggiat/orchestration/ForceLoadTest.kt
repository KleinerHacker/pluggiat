package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.classloader.PluginLoadResult
import org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.ChecksumSecurityStrategy
import java.nio.file.Path

/**
 * Verifies [org.pcsoft.framework.pluggiat.PluginManager.forceLoad]: loading a candidate rejected by
 * the security chain despite that rejection, without altering the original `scanResults` entry.
 */
class ForceLoadTest {

    /**
     * Use case: a candidate with no known expected checksum fails the `ChecksumSecurityStrategy`
     * chain (`SECURITY_PROBLEM`) during `scan()`; `forceLoad` still loads it, and `loadedPlugins`
     * reflects it afterward, while the original `scanResults` entry keeps its `SECURITY_PROBLEM`
     * status unchanged.
     */
    @Test
    fun `forceLoad loads a candidate that failed security without changing its scanResults entry`(@TempDir tempDir: Path) {
        val store = mutableMapOf<String, String>()
        val persistence = CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )

        val manager = pluginManager {
            persistenceStrategy = persistence
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(ChecksumSecurityStrategy(persistence))
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
        manager.scan()
        val originalEntry = manager.scanResults.single()
        assertEquals(PluginScanStatus.SECURITY_PROBLEM, originalEntry.status)

        val result = manager.forceLoad("plugin-a")

        assertTrue(result is PluginLoadResult.Loaded)
        assertTrue(manager.loadedPlugins.containsKey("plugin-a"))
        assertEquals(originalEntry, manager.scanResults.single())
    }
}
