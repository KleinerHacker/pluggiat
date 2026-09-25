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

import org.junit.jupiter.api.Assertions.assertThrows
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
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

/**
 * Verifies [org.pcsoft.framework.pluggiat.PluginManager.write], the typed, strategy-specific
 * alternative to a permanent security exception for a [org.pcsoft.framework.pluggiat.security.PersistableSecurityStrategy]
 * like [ChecksumSecurityStrategy].
 */
class PersistableSecurityStrategyTest {

    /**
     * Use case: after a force-load, `write<ChecksumSecurityStrategy>(pluginId)` persists the
     * candidate's actual checksum as the new expected checksum, so a later `reload` succeeds through
     * the regular chain.
     */
    @Test
    fun `write persists the actual checksum, a later reload then succeeds`(@TempDir tempDir: Path) {
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
        assertTrue(manager.scanResults.single().status == PluginScanStatus.SECURITY_PROBLEM)
        manager.forceLoad("plugin-a")

        manager.write<ChecksumSecurityStrategy>("plugin-a")
        val reloadResult = manager.reload("plugin-a")

        assertTrue(reloadResult is PluginLoadResult.Loaded)
    }

    /**
     * Use case: `write<T>` for a strategy type not present in the plugin's effective chain throws a
     * clear error instead of silently doing nothing.
     */
    @Test
    fun `write throws for a strategy type not configured for the plugin`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
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

        assertThrows(IllegalStateException::class.java) {
            manager.write<ChecksumSecurityStrategy>("plugin-a")
        }
    }
}
