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

package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.classloader.DisallowPluginDependencyStrategy
import org.pcsoft.framework.pluggiat.extension.ExporterTestConfig
import org.pcsoft.framework.pluggiat.extension.LifecycleRecordingTestExporter
import org.pcsoft.framework.pluggiat.extension.TestExporter
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

/**
 * End-to-end [org.pcsoft.framework.pluggiat.PluginManager] tests exercising a real extension
 * implementation resolved through an isolated plugin class loader (the test fixture package is
 * whitelisted so the isolated loader delegates to the test JVM's own class loader) - covers typed
 * extension access, lifecycle hooks on `unload`, runtime `UNLOAD` deactivation, and load-time
 * failure paths (missing dependency, cyclic dependency).
 */
class PluginManagerOrchestrationTest {

    private fun extensionManifestYaml(id: String, implementation: String): String = """
        ${'$'}version: 1
        id: $id
        name: $id
        version: "1.0.0"
        minVersion: "1.0.0"
        icon: aWNvbg==
        extensions:
          exporters:
            - implementation: $implementation
              fileExtension: csv
    """.trimIndent()

    private fun managerWithWhitelistedFixtures(tempDir: Path) = pluginManager {
        defaultSecurityChain {
            type = PluginLocationType.EXTERNAL
            addStrategy(InsecureSecurityStrategy())
        }
        sdkWhitelistEntry {
            packageName = "org.pcsoft.framework.pluggiat"
        }
        extensionPoint(ExporterTestConfig::class)
        location {
            path = tempDir
            type = PluginLocationType.EXTERNAL
            scanStrategy = SingleJarScanStrategy()
        }
    }

    /**
     * Use case: `getExtensions<T>`/`getFirstExtension<T>` return the currently active, typed
     * extension implementations after a successful `scan()`.
     */
    @Test
    fun `getExtensions and getFirstExtension return the loaded implementation`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to extensionManifestYaml("plugin-a", "org.pcsoft.framework.pluggiat.extension.CsvTestExporter")),
        )
        val manager = managerWithWhitelistedFixtures(tempDir)

        manager.scan()

        val all: List<TestExporter> = manager.getExtensions("exporters")
        val first: TestExporter? = manager.getFirstExtension("exporters")
        assertEquals(1, all.size)
        assertNotNull(first)
        assertEquals(emptyList<TestExporter>(), manager.getExtensions<TestExporter>("unknown-key"))
        assertNull(manager.getFirstExtension<TestExporter>("unknown-key"))
    }

    /**
     * Use case: `unload` invokes `onDisable`/`onUnload` on the plugin's real (unproxied) extension
     * instance before persisting it as disabled and closing its class loader.
     */
    @Test
    fun `unload invokes lifecycle hooks on the real extension instance`(@TempDir tempDir: Path) {
        LifecycleRecordingTestExporter.callOrder.clear()
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to extensionManifestYaml("plugin-a", "org.pcsoft.framework.pluggiat.extension.LifecycleRecordingTestExporter")),
        )
        val manager = managerWithWhitelistedFixtures(tempDir)
        manager.scan()
        assertEquals(listOf("onLoad", "onEnable"), LifecycleRecordingTestExporter.callOrder)

        manager.unload("plugin-a")

        assertEquals(listOf("onLoad", "onEnable", "onDisable", "onUnload"), LifecycleRecordingTestExporter.callOrder)
        assertFalse(manager.loadedPlugins.containsKey("plugin-a"))
    }

    /**
     * Use case: an unhandled runtime exception escaping an extension call resolves to `UNLOAD` by
     * the default exception handling strategy, which forcibly closes the plugin's class loader and
     * removes it from `loadedPlugins`/`extensionsByKey`.
     */
    @Test
    fun `a runtime UNLOAD action closes the plugin and removes it from loadedPlugins`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to extensionManifestYaml("plugin-a", "org.pcsoft.framework.pluggiat.extension.FailingTestExporter")),
        )
        val manager = managerWithWhitelistedFixtures(tempDir)
        manager.scan()
        val exporter: TestExporter = manager.getFirstExtension("exporters")!!

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            exporter.name()
        }

        assertFalse(manager.loadedPlugins.containsKey("plugin-a"))
    }

    /**
     * Use case: a plugin declaring a missing `required` dependency passes every earlier check but
     * fails at `PluginLoader.load`, ending up as `LOAD_FAILED` rather than `LOADED`.
     */
    @Test
    fun `a missing required dependency results in LOAD_FAILED`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf(
                "META-INF/plugin.yml" to (
                    PluginScannerTestFixtures.validManifestYaml("plugin-a") + "\n" +
                        "dependencies:\n  - id: missing-dependency\n    required: true"
                    ),
            ),
        )
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

        manager.scan()

        assertEquals(PluginScanStatus.LOAD_FAILED, manager.scanResults.single().status)
        assertTrue(manager.loadedPlugins.isEmpty())
    }

    /**
     * Use case: two plugins mutually requiring each other form a dependency cycle - no candidate of
     * that `scan()` run is loaded, both end up `LOAD_FAILED` with the cycle in `errorMessage`.
     */
    @Test
    fun `a cyclic dependency prevents every candidate of that scan from loading`(@TempDir tempDir: Path) {
        val yamlA = PluginScannerTestFixtures.validManifestYaml("plugin-a") + "\ndependencies:\n  - id: plugin-b\n    required: true"
        val yamlB = PluginScannerTestFixtures.validManifestYaml("plugin-b") + "\ndependencies:\n  - id: plugin-a\n    required: true"
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-a.jar"), mapOf("META-INF/plugin.yml" to yamlA))
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-b.jar"), mapOf("META-INF/plugin.yml" to yamlB))
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

        manager.scan()

        assertTrue(manager.loadedPlugins.isEmpty())
        assertTrue(manager.scanResults.all { it.status == PluginScanStatus.LOAD_FAILED })
        assertTrue(manager.scanResults.all { it.errorMessage?.contains("Cyclic") == true })
    }

    /**
     * Use case: a plugin declaring a `required` dependency that is actually loaded alongside it is
     * loaded successfully, with the dependency passed through to `PluginLoader.load` - across
     * `scan()`, `reload()` and `forceLoad()` alike.
     */
    @Test
    fun `a satisfied required dependency is passed through on scan, reload and forceLoad`(@TempDir tempDir: Path) {
        val yamlA = PluginScannerTestFixtures.validManifestYaml("plugin-a")
        val yamlB = PluginScannerTestFixtures.validManifestYaml("plugin-b") + "\ndependencies:\n  - id: plugin-a\n    required: true"
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-a.jar"), mapOf("META-INF/plugin.yml" to yamlA))
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-b.jar"), mapOf("META-INF/plugin.yml" to yamlB))
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

        manager.scan()
        assertEquals(setOf("plugin-a", "plugin-b"), manager.loadedPlugins.keys)

        assertTrue(manager.reload("plugin-b") is org.pcsoft.framework.pluggiat.classloader.PluginLoadResult.Loaded)
        assertTrue(manager.forceLoad("plugin-b") is org.pcsoft.framework.pluggiat.classloader.PluginLoadResult.Loaded)
    }

    /**
     * Use case: an explicit `dependencyStrategyOverride` on a location is honored during `scan()` -
     * `DisallowPluginDependencyStrategy` makes even a same-location `required` dependency invisible,
     * so the dependent plugin ends up `LOAD_FAILED` although its dependency itself loads fine.
     */
    @Test
    fun `dependencyStrategyOverride governs cross-plugin dependency visibility during scan`(@TempDir tempDir: Path) {
        val yamlA = PluginScannerTestFixtures.validManifestYaml("plugin-a")
        val yamlB = PluginScannerTestFixtures.validManifestYaml("plugin-b") + "\ndependencies:\n  - id: plugin-a\n    required: true"
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-a.jar"), mapOf("META-INF/plugin.yml" to yamlA))
        PluginScannerTestFixtures.writeJar(tempDir.resolve("plugin-b.jar"), mapOf("META-INF/plugin.yml" to yamlB))
        val manager = pluginManager {
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(InsecureSecurityStrategy())
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
                dependencyStrategyOverride = DisallowPluginDependencyStrategy()
            }
        }

        manager.scan()

        assertTrue(manager.loadedPlugins.containsKey("plugin-a"))
        assertFalse(manager.loadedPlugins.containsKey("plugin-b"))
        assertEquals(PluginScanStatus.LOAD_FAILED, manager.scanResults.first { it.manifest?.id == "plugin-b" }.status)
    }

    /**
     * Use case: `unload` is a no-op regarding lifecycle hooks for a real extension instance that does
     * not implement `PluginLifecycle` - it is still deactivated normally.
     */
    @Test
    fun `unload deactivates a real extension instance that has no lifecycle hooks`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to extensionManifestYaml("plugin-a", "org.pcsoft.framework.pluggiat.extension.CsvTestExporter")),
        )
        val manager = managerWithWhitelistedFixtures(tempDir)
        manager.scan()
        assertEquals(1, manager.getExtensions<TestExporter>("exporters").size)

        manager.unload("plugin-a")

        assertFalse(manager.loadedPlugins.containsKey("plugin-a"))
        assertTrue(manager.getExtensions<TestExporter>("exporters").isEmpty())
    }

    /**
     * Use case: `getExtensions<T>`/`getFirstExtension<T>` silently skip entries whose real instance is
     * not assignable to the requested type `T`, instead of throwing a `ClassCastException`.
     */
    @Test
    fun `getExtensions and getFirstExtension skip entries not assignable to T`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to extensionManifestYaml("plugin-a", "org.pcsoft.framework.pluggiat.extension.CsvTestExporter")),
        )
        val manager = managerWithWhitelistedFixtures(tempDir)
        manager.scan()

        val wronglyTyped: List<Runnable> = manager.getExtensions("exporters")
        val firstWronglyTyped: Runnable? = manager.getFirstExtension("exporters")

        assertTrue(wronglyTyped.isEmpty())
        assertNull(firstWronglyTyped)
    }

    /**
     * Use case: `write<T>` skips a candidate without a resolved manifest while searching
     * `scanResults` for the requested plugin id, instead of failing on it.
     */
    @Test
    fun `write finds the right candidate even when scanResults also contains one without a manifest`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin-a.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin-b.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.invalidManifestYaml("plugin-b")),
        )
        val store = mutableMapOf<String, String>()
        val persistence = org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )
        val manager = pluginManager {
            persistenceStrategy = persistence
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(org.pcsoft.framework.pluggiat.security.ChecksumSecurityStrategy(persistence))
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
        manager.scan()
        manager.forceLoad("plugin-a")

        manager.write<org.pcsoft.framework.pluggiat.security.ChecksumSecurityStrategy>("plugin-a")

        assertTrue(manager.reload("plugin-a") is org.pcsoft.framework.pluggiat.classloader.PluginLoadResult.Loaded)
    }
}
