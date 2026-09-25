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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregator
import org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

/**
 * Verifies [org.pcsoft.framework.pluggiat.PluginManager.unload]: deliberate, host-initiated
 * deactivation of a loaded plugin.
 */
class UnloadTest {

    /**
     * Use case: unloading a loaded plugin persists it as disabled with
     * [ExtensionAggregator.USER_REASON], and removes it from `loadedPlugins`/`extensionsByKey`.
     */
    @Test
    fun `unload persists USER_REASON and removes the plugin from loadedPlugins`(@TempDir tempDir: Path) {
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
                addStrategy(InsecureSecurityStrategy())
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
        manager.scan()
        assertEquals(setOf("plugin-a"), manager.loadedPlugins.keys)

        manager.unload("plugin-a")

        assertFalse(manager.loadedPlugins.containsKey("plugin-a"))
        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
        assertEquals(ExtensionAggregator.USER_REASON, store["plugin-a.${ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY}"])
    }

    /**
     * Use case: unloading a plugin id that is not currently loaded is a no-op.
     */
    @Test
    fun `unload is a no-op for a plugin that is not loaded`() {
        val manager = pluginManager {}

        manager.unload("unknown-plugin")

        assertEquals(emptyMap<String, Any>(), manager.loadedPlugins)
    }
}
