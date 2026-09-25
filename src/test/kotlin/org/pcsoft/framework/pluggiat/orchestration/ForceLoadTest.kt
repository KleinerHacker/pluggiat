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
