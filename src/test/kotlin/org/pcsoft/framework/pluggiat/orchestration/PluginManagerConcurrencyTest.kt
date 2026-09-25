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
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies that [org.pcsoft.framework.pluggiat.PluginManager]'s public API is safely callable from
 * multiple threads concurrently, without corrupting `scanResults`/`loadedPlugins`.
 */
class PluginManagerConcurrencyTest {

    /**
     * Use case: `scan`/`reload`/`unload`/`forceLoad` are invoked concurrently from several threads
     * on the same [org.pcsoft.framework.pluggiat.PluginManager] instance; no exception escapes and
     * the instance is left in a consistent, still-usable state.
     */
    @Test
    fun `scan, reload, unload and forceLoad are safely callable from multiple threads`(@TempDir tempDir: Path) {
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

        val executor = Executors.newFixedThreadPool(4)
        val errors = mutableListOf<Throwable>()
        repeat(20) { i ->
            executor.submit {
                try {
                    when (i % 4) {
                        0 -> manager.scan()
                        1 -> runCatching { manager.reload("plugin-a") }
                        2 -> manager.unload("plugin-a")
                        else -> runCatching { manager.forceLoad("plugin-a") }
                    }
                } catch (e: Throwable) {
                    synchronized(errors) { errors += e }
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        assertTrue(errors.isEmpty()) { "Unexpected exceptions from concurrent access: $errors" }
        manager.scan()
        assertTrue(manager.loadedPlugins.containsKey("plugin-a"))
    }
}
