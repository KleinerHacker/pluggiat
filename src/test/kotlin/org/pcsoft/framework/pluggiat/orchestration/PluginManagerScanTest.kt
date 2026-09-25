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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path

/**
 * Verifies [org.pcsoft.framework.pluggiat.PluginManager.scan]'s end-to-end orchestration: scanning
 * multiple locations, loading security-passed candidates and reflecting a mixed result on
 * `scanResults`/`loadedPlugins`.
 */
class PluginManagerScanTest {

    /**
     * Use case: two locations, one valid and one invalid plugin candidate - `scan()` loads the
     * valid one and reports the invalid one in `scanResults` without loading it.
     */
    @Test
    fun `scan loads valid candidates and reports invalid ones without loading them`(@TempDir tempDir: Path) {
        val locationA = tempDir.resolve("location-a").also { java.nio.file.Files.createDirectories(it) }
        val locationB = tempDir.resolve("location-b").also { java.nio.file.Files.createDirectories(it) }
        PluginScannerTestFixtures.writeJar(
            locationA.resolve("plugin-a.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        PluginScannerTestFixtures.writeJar(
            locationB.resolve("plugin-b.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.invalidManifestYaml("plugin-b")),
        )

        val manager = pluginManager {
            defaultSecurityChain {
                type = PluginLocationType.EXTERNAL
                addStrategy(InsecureSecurityStrategy())
            }
            location {
                path = locationA
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
            location {
                path = locationB
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }

        manager.scan()

        assertEquals(setOf("plugin-a"), manager.loadedPlugins.keys)
        val byStatus = manager.scanResults.groupBy { it.status }
        assertEquals(1, byStatus[PluginScanStatus.LOADED]?.size)
        assertEquals(1, byStatus[PluginScanStatus.MANIFEST_INVALID]?.size)
    }

    /**
     * Use case: a re-`scan()` for a location whose plugin file has meanwhile been deleted closes the
     * previously loaded plugin's class loader and removes it from `loadedPlugins`.
     */
    @Test
    fun `a plugin missing on rescan is closed and removed from loadedPlugins`(@TempDir tempDir: Path) {
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
        manager.scan()
        assertTrue(manager.loadedPlugins.containsKey("plugin-a"))

        java.nio.file.Files.delete(jarPath)
        manager.scan()

        assertTrue(manager.loadedPlugins.isEmpty())
    }
}
