package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies [org.pcsoft.framework.pluggiat.PluginManager.scan]'s end-to-end id collision resolution
 * and `minVersion` enforcement, on top of the isolated [IdCollisionResolver]/[MinVersionChecker] unit
 * tests.
 */
class IdCollisionAndMinVersionIntegrationTest {

    private fun managerFor(vararg locations: Path, hostVersion: String? = null) = pluginManager {
        this.hostVersion = hostVersion
        defaultSecurityChain {
            type = PluginLocationType.EXTERNAL
            addStrategy(InsecureSecurityStrategy())
        }
        for (path in locations) {
            location {
                this.path = path
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
    }

    /**
     * Use case: two locations both contribute a candidate with the same plugin id, one at a higher
     * version - only the higher-version candidate ends up in `loadedPlugins`, the other is rejected
     * with `ID_COLLISION`.
     */
    @Test
    fun `higher version wins across two locations, the other is not loaded`(@TempDir tempDir: Path) {
        val locationA = tempDir.resolve("a").also(Files::createDirectories)
        val locationB = tempDir.resolve("b").also(Files::createDirectories)
        PluginScannerTestFixtures.writeJar(
            locationA.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a", version = "1.0.0")),
        )
        PluginScannerTestFixtures.writeJar(
            locationB.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a", version = "2.0.0")),
        )

        val manager = managerFor(locationA, locationB)
        manager.scan()

        assertEquals(setOf("plugin-a"), manager.loadedPlugins.keys)
        assertEquals("2.0.0", manager.loadedPlugins.getValue("plugin-a").manifest.version)
        assertEquals(1, manager.scanResults.count { it.status == PluginScanStatus.ID_COLLISION })
    }

    /**
     * Use case: a plugin declaring a `minVersion` above the configured host version is rejected with
     * `MIN_VERSION_VIOLATION` and never loaded.
     */
    @Test
    fun `plugin requiring a newer host version is not loaded`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a", minVersion = "5.0.0")),
        )

        val manager = managerFor(tempDir, hostVersion = "1.0.0")
        manager.scan()

        assertEquals(emptySet<String>(), manager.loadedPlugins.keys)
        assertEquals(PluginScanStatus.MIN_VERSION_VIOLATION, manager.scanResults.single().status)
    }
}
