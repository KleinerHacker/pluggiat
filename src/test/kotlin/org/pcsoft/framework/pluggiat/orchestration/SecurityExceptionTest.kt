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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.classloader.PluginLoadResult
import org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import java.nio.file.Path

/**
 * Verifies the generic, persistent security exception set by
 * [org.pcsoft.framework.pluggiat.PluginManager.forceLoad] with `persistException = true`, for
 * strategies without dedicated `PersistableSecurityStrategy` support (e.g. a signature strategy).
 */
class SecurityExceptionTest {

    private val alwaysFailingStrategy = object : PluginSecurityStrategy {
        override fun check(result: PluginScanResult) = PluginSecurityCheckResult.Failure("always fails")
    }

    /**
     * Use case: `forceLoad(pluginId, persistException = true)` records a permanent exception; a
     * subsequent `reload` then succeeds through the regular chain despite the strategy still always
     * failing on its own.
     */
    @Test
    fun `persistException lets a later reload succeed despite the strategy still failing`(@TempDir tempDir: Path) {
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
                addStrategy(alwaysFailingStrategy)
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
        manager.scan()
        assertTrue(manager.scanResults.single().status == PluginScanStatus.SECURITY_PROBLEM)

        manager.forceLoad("plugin-a", persistException = true)
        val reloadResult = manager.reload("plugin-a")

        assertTrue(reloadResult is PluginLoadResult.Loaded)
    }

    /**
     * Use case: without `persistException`, a later `reload` still fails against the same
     * always-failing strategy.
     */
    @Test
    fun `without persistException a later reload still fails`(@TempDir tempDir: Path) {
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
                addStrategy(alwaysFailingStrategy)
            }
            location {
                path = tempDir
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
            }
        }
        manager.scan()

        manager.forceLoad("plugin-a")
        val reloadResult = manager.reload("plugin-a")

        assertTrue(reloadResult is PluginLoadResult.Invalid)
    }
}
