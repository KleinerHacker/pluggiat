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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.PluginManager
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregator
import org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

/**
 * Verifies [PluginManager]'s real handling behind [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.reportViolation]
 * for a category-attributed sandbox violation (IP-02): immediate unload, `POTENTIAL_ATTACK` status,
 * persisted disable reason, and the resulting `reload`/`reactivate`/`forceLoad` block.
 */
class SandboxViolationHandlingTest {

    private fun managerWithLoadedPlugin(tempDir: Path, store: MutableMap<String, String>): PluginManager {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        val persistence = CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )
        val manager = pluginManager {
            persistenceStrategy = persistence
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
        assertEquals(PluginScanStatus.LOADED, manager.scanResults.single().status)
        assertTrue("plugin-a" in manager.loadedPlugins)
        return manager
    }

    /**
     * Use case: a category-attributed [SandboxViolation] reported for a loaded plugin forcibly
     * unloads it, marks its `scanResults` entry `POTENTIAL_ATTACK` with the violation's reason, and
     * persists the disable reason as [PluginManager.SANDBOX_ATTACK_REASON].
     */
    @Test
    fun `a category-attributed violation unloads the plugin and marks it POTENTIAL_ATTACK`(@TempDir tempDir: Path) {
        val store = mutableMapOf<String, String>()
        val manager = managerWithLoadedPlugin(tempDir, store)

        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", SandboxApiCategory.NETWORK, "blocked network access"))

        assertFalse("plugin-a" in manager.loadedPlugins)
        val entry = manager.scanResults.single()
        assertEquals(PluginScanStatus.POTENTIAL_ATTACK, entry.status)
        assertEquals("blocked network access", entry.errorMessage)
        assertEquals(PluginManager.SANDBOX_ATTACK_REASON, store["plugin-a.${ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY}"])
        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
    }

    /**
     * Use case: once a plugin is marked `POTENTIAL_ATTACK`, `PluginManager.forceLoad` refuses to load
     * it again - unlike every other non-`LOADED` status, there is no host override for this one.
     */
    @Test
    fun `forceLoad refuses a plugin marked POTENTIAL_ATTACK`(@TempDir tempDir: Path) {
        val manager = managerWithLoadedPlugin(tempDir, mutableMapOf())
        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", SandboxApiCategory.FILESYSTEM, "blocked file access"))

        assertThrows(IllegalStateException::class.java) {
            manager.forceLoad("plugin-a")
        }
    }

    /**
     * Use case: once a plugin is marked `POTENTIAL_ATTACK`, `PluginManager.reload` refuses to bring it
     * back even though its security chain (insecure, always accepting) would pass the re-check - the
     * refusal happens before anything is re-checked, and the loaded plugins, the scan results and
     * the persisted state all stay exactly as the forced unload left them.
     */
    @Test
    fun `reload refuses a plugin marked POTENTIAL_ATTACK and leaves the state unchanged`(@TempDir tempDir: Path) {
        val store = mutableMapOf<String, String>()
        val manager = managerWithLoadedPlugin(tempDir, store)
        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", SandboxApiCategory.NETWORK, "blocked network access"))
        val loadedBefore = manager.loadedPlugins
        val scanResultsBefore = manager.scanResults
        val storeBefore = store.toMap()

        val exception = assertThrows(IllegalStateException::class.java) {
            manager.reload("plugin-a")
        }

        assertTrue(exception.message!!.contains("POTENTIAL_ATTACK"))
        assertEquals(loadedBefore, manager.loadedPlugins)
        assertFalse("plugin-a" in manager.loadedPlugins)
        assertEquals(scanResultsBefore, manager.scanResults)
        assertEquals(PluginScanStatus.POTENTIAL_ATTACK, manager.scanResults.single().status)
        assertEquals(storeBefore, store.toMap())
    }

    /**
     * Use case: `PluginManager.reactivate` is public and takes a location, path and manifest instead of
     * a plugin id, so it must not be a way around the block - called with the manifest of a plugin marked
     * `POTENTIAL_ATTACK` it throws before the security re-check, and never persists the plugin as enabled again.
     */
    @Test
    fun `reactivate refuses a plugin marked POTENTIAL_ATTACK and never persists it as enabled`(@TempDir tempDir: Path) {
        val store = mutableMapOf<String, String>()
        val manager = managerWithLoadedPlugin(tempDir, store)
        val entry = manager.scanResults.single()
        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", SandboxApiCategory.NETWORK, "blocked network access"))
        val storeBefore = store.toMap()

        assertThrows(IllegalStateException::class.java) {
            manager.reactivate(entry.location, entry.path, entry.manifest!!)
        }

        assertFalse("plugin-a" in manager.loadedPlugins)
        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
        assertEquals(storeBefore, store.toMap())
    }

    /**
     * Use case: a single category-less violation (an IP-03 time-limit violation) does not yet unload
     * the plugin - [PluginManager.handleSandboxViolation] only escalates to a forced unload once
     * [PluginManager.MAX_TIMEOUT_VIOLATIONS] cumulative category-less violations are reported for the
     * same plugin id (see `PluginManagerOrchestrationTest`'s escalation test), so a lone violation
     * leaves the plugin loaded with no status change.
     */
    @Test
    fun `a single category-less violation does not yet escalate to an unload`(@TempDir tempDir: Path) {
        val manager = managerWithLoadedPlugin(tempDir, mutableMapOf())

        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", null, "hypothetical timeout"))

        assertTrue("plugin-a" in manager.loadedPlugins)
        assertEquals(PluginScanStatus.LOADED, manager.scanResults.single().status)
    }
}
