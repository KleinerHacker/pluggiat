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

package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginDependency
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScanner
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import java.nio.file.Path

/**
 * Developer tests for [PluginLoader]: missing dependencies and unconditional loading regardless of
 * any prior security outcome.
 */
class PluginLoaderTest {

    @TempDir
    private lateinit var tempDir: Path

    private fun manifest(id: String, vararg dependencies: PluginDependency) = PluginManifest(
        id = id, name = id, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==",
        dependencies = dependencies.toList(),
    )

    /**
     * A plugin declaring a `required` dependency that is absent from the [PluginLoader.load]
     * `dependencies` map must be reported as invalid, without being loaded.
     */
    @Test
    fun `missing required dependency invalidates plugin`() {
        val pluginPath = tempDir.resolve("plugin-a.jar")
        PluginLoaderTestFixtures.writeSingleJarPlugin(pluginPath, "a")
        val manifest = manifest("a", PluginDependency("b", required = true))

        val result = PluginLoader().load(pluginPath, manifest)

        assertTrue(result is PluginLoadResult.Invalid)
        assertEquals("a", (result as PluginLoadResult.Invalid).pluginId)
    }

    /**
     * A plugin declaring an `optional` dependency that is absent from the [PluginLoader.load]
     * `dependencies` map must still load successfully, simply without that dependency wired in.
     */
    @Test
    fun `missing optional dependency still loads plugin`() {
        val pluginPath = tempDir.resolve("plugin-a.jar")
        PluginLoaderTestFixtures.writeSingleJarPlugin(pluginPath, "a")
        val manifest = manifest("a", PluginDependency("b", required = false))

        val result = PluginLoader().load(pluginPath, manifest)

        assertTrue(result is PluginLoadResult.Loaded)
    }

    /**
     * [PluginLoader.load] must load a plugin unconditionally, even one whose candidate previously
     * failed every strategy of its location's security chain - [PluginLoader] performs no security
     * check of its own, that decision (and any resulting logging) is entirely up to the caller.
     */
    @Test
    fun `load succeeds regardless of a prior failed security check`() {
        val pluginPath = tempDir.resolve("plugin-a.jar")
        PluginLoaderTestFixtures.writeSingleJarPlugin(pluginPath, "a")
        val alwaysFailingStrategy = object : PluginSecurityStrategy {
            override fun check(result: org.pcsoft.framework.pluggiat.scanner.PluginScanResult) =
                PluginSecurityCheckResult.Failure("test failure")
        }
        val location = PluginLocation(
            path = tempDir, type = PluginLocationType.EXTERNAL,
            scanStrategy = SingleJarScanStrategy(), securityOverride = listOf(alwaysFailingStrategy),
        )
        val scanResult = PluginScanner().scan(listOf(location)).single()
        assertEquals(PluginScanStatus.SECURITY_PROBLEM, scanResult.status)
        val manifest = manifest("a")

        val result = PluginLoader().load(pluginPath, manifest)

        assertTrue(result is PluginLoadResult.Loaded)
    }

    /**
     * Use case: loading a candidate from its already-pinned [org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent]
     * (as [PluginScanner] produces for every successfully checked candidate) uses exactly the bytes
     * that were pinned, even if the file on disk is swapped out afterward - the TOCTOU window between
     * a security check and the actual load is closed.
     */
    @Test
    fun `loading pinned content uses the pinned bytes even after the file on disk is swapped`() {
        val pluginPath = tempDir.resolve("plugin-a.jar")
        org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.writeJar(
            pluginPath,
            mapOf(
                "META-INF/plugin.yml" to org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.validManifestYaml("a"),
                "marker.txt" to "original",
            ),
        )
        val alwaysSucceedingStrategy = object : PluginSecurityStrategy {
            override fun check(result: org.pcsoft.framework.pluggiat.scanner.PluginScanResult) = PluginSecurityCheckResult.Success
        }
        val location = PluginLocation(
            path = tempDir, type = PluginLocationType.EXTERNAL,
            scanStrategy = SingleJarScanStrategy(), securityOverride = listOf(alwaysSucceedingStrategy),
        )
        val scanResult = PluginScanner().scan(listOf(location)).single()
        assertEquals(PluginScanStatus.LOADED, scanResult.status)
        val pinnedContent = requireNotNull(scanResult.pinnedContent)

        // Swap the file on disk after pinning, before loading - simulates an attacker replacing the
        // candidate in the TOCTOU window between security check and load.
        org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.writeJar(
            pluginPath,
            mapOf(
                "META-INF/plugin.yml" to org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.validManifestYaml("a"),
                "marker.txt" to "swapped",
            ),
        )

        val result = PluginLoader().load(pinnedContent, manifest("a"))

        assertTrue(result is PluginLoadResult.Loaded)
        val markerContent = (result as PluginLoadResult.Loaded).plugin.classLoader
            .getResourceAsStream("marker.txt")!!.bufferedReader().readText()
        assertEquals("original", markerContent)
    }
}
