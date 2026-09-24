package org.pcsoft.framework.pluggiat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.classloader.PluginLoadResult
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregator
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

class PluginManagerTest {

    /**
     * Use case: the [pluginManager] builder DSL wires [PluginManager.scanner]/[PluginManager.security]/
     * [PluginManager.loader] into usable instances from the [PluginManagerConfiguration] filled in by the DSL block.
     */
    @Test
    fun `builder DSL produces correctly wired scanner, security and loader instances`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))

        val manager = pluginManager {
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(InsecureSecurityStrategy())
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }

        assertNotNull(manager.scanner)
        assertNotNull(manager.security)
        assertNotNull(manager.loader)

        val results = manager.scanner.scan(manager.config.pluginLocations)
        assertEquals(1, results.size)
        assertEquals("plugin-a", results.single().manifest?.id)
    }

    /**
     * Use case: [PluginManager.reactivate] re-checks the security chain first; a successful
     * re-check reloads the plugin via [PluginManager.loader] and persists it as enabled again.
     */
    @Test
    fun `reactivate reloads and persists the plugin as enabled after a successful re-check`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val store = mutableMapOf<String, String>()
        val manager = pluginManager {
            persistenceStrategy = CustomPersistenceStrategy(
                readCallback = { pluginId, key -> store["$pluginId.$key"] },
                writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
            )
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(InsecureSecurityStrategy())
            }
        }
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

        val result = manager.reactivate(location, jarPath, manifest)

        assertTrue(result is PluginLoadResult.Loaded)
        assertEquals("true", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
    }

    /**
     * Use case: a failed security re-check keeps the plugin disabled (persisted reason updated) and
     * never falls back to a force-load.
     */
    @Test
    fun `reactivate keeps the plugin disabled and does not load on a failed re-check`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val store = mutableMapOf<String, String>()
        val alwaysFailingStrategy = object : org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy {
            override fun check(result: org.pcsoft.framework.pluggiat.scanner.PluginScanResult) =
                org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult.Failure("re-check rejected")
        }
        val manager = pluginManager {
            persistenceStrategy = CustomPersistenceStrategy(
                readCallback = { pluginId, key -> store["$pluginId.$key"] },
                writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
            )
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(alwaysFailingStrategy)
            }
        }
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

        val result = manager.reactivate(location, jarPath, manifest)

        assertTrue(result is PluginLoadResult.Invalid)
        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
        assertEquals(PluginManager.SECURITY_RECHECK_FAILED_REASON, store["plugin-a.${ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY}"])
    }

    /**
     * Use case: a successful security re-check does not guarantee a successful reload - a candidate
     * with a missing `required` dependency still fails at [PluginManager.loader], without the
     * re-check-failure persistence path being taken.
     */
    @Test
    fun `reactivate does not persist as enabled when the loader itself fails after a successful re-check`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))
        val store = mutableMapOf<String, String>()
        val manager = pluginManager {
            persistenceStrategy = CustomPersistenceStrategy(
                readCallback = { pluginId, key -> store["$pluginId.$key"] },
                writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
            )
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(InsecureSecurityStrategy())
            }
        }
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(
            id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==",
            dependencies = listOf(org.pcsoft.framework.pluggiat.manifest.PluginDependency(id = "missing-dependency", required = true)),
        )

        val result = manager.reactivate(location, jarPath, manifest)

        assertTrue(result is PluginLoadResult.Invalid)
        assertEquals(null, store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
    }
}
