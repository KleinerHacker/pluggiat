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

package org.pcsoft.framework.pluggiat.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import java.nio.file.Path

class PersistableSecurityStrategyTest {

    /**
     * Use case: a strategy that only implements the path-based [PersistableSecurityStrategy.persist]
     * is also usable through the pinned-content overload, whose default implementation delegates to it
     * (for a strategy that derives nothing from the candidate's content).
     */
    @Test
    fun `pinned persist defaults to the path-based persist`() {
        val persisted = mutableListOf<String>()
        val strategy = object : PersistableSecurityStrategy {
            override fun check(result: PluginScanResult): PluginSecurityCheckResult = PluginSecurityCheckResult.Success

            override fun persist(pluginId: String, result: PluginScanResult) {
                persisted += pluginId
            }
        }
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")
        val result = PluginScanResult(location, Path.of("plugin-a.jar"), manifest, PluginScanStatus.LOADED)

        strategy.persist("plugin-a", result, PinnedPluginContent.Single(ByteArray(0)))

        assertEquals(listOf("plugin-a"), persisted)
    }
}
