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
 * persisted disable reason, and the resulting `forceLoad` block.
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
     * Use case: a category-less violation (reserved for a future IP-03 time-limit violation) is left
     * untouched by [PluginManager]'s handler as of IP-02 - the plugin stays loaded, no status change.
     */
    @Test
    fun `a category-less violation is not acted upon yet`(@TempDir tempDir: Path) {
        val manager = managerWithLoadedPlugin(tempDir, mutableMapOf())

        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", null, "hypothetical timeout"))

        assertTrue("plugin-a" in manager.loadedPlugins)
        assertEquals(PluginScanStatus.LOADED, manager.scanResults.single().status)
    }
}
