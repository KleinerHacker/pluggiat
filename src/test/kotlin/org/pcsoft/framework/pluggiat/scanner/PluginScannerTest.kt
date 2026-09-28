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

package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginScannerTest {

    private val insecureDefaults = mapOf(
        PluginLocationType.BUILTIN to listOf(InsecureSecurityStrategy()),
        PluginLocationType.EXTERNAL to listOf(InsecureSecurityStrategy()),
    )

    /**
     * Use case: scanning several locations with different scan strategies combines all of their
     * results into one flat list, each result carrying its own originating location.
     */
    @Test
    fun `scans multiple locations of different strategies and combines their results`(@TempDir tempDir: Path) {
        val singleJarLocationDir = Files.createDirectory(tempDir.resolve("single-jar-location"))
        PluginScannerTestFixtures.writeJar(
            singleJarLocationDir.resolve("plugin-a.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        val singleJarLocation = PluginLocation(singleJarLocationDir, PluginLocationType.BUILTIN, SingleJarScanStrategy())

        val zipLocationDir = Files.createDirectory(tempDir.resolve("zip-location"))
        val pluginBJar = tempDir.resolve("plugin-b.jar")
        PluginScannerTestFixtures.writeJar(
            pluginBJar,
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.invalidManifestYaml("plugin-b")),
        )
        ZipOutputStream(Files.newOutputStream(zipLocationDir.resolve("plugin-b.zip"))).use { zipStream ->
            zipStream.putNextEntry(ZipEntry("plugin-b.jar"))
            zipStream.write(Files.readAllBytes(pluginBJar))
            zipStream.closeEntry()
        }
        val zipLocation = PluginLocation(zipLocationDir, PluginLocationType.EXTERNAL, ZipJarScanStrategy())

        val results = PluginScanner(insecureDefaults).scan(listOf(singleJarLocation, zipLocation))

        assertEquals(2, results.size)
        assertTrue(results.any { it.location == singleJarLocation && it.status == PluginScanStatus.LOADED })
        assertTrue(results.any { it.location == zipLocation && it.status == PluginScanStatus.MANIFEST_INVALID })
    }

    /**
     * Use case: scanning an empty list of locations produces an empty result without error.
     */
    @Test
    fun `scanning no locations yields an empty result`() {
        val results = PluginScanner(insecureDefaults).scan(emptyList())

        assertTrue(results.isEmpty())
    }

    /**
     * Use case: a location without any configured security strategy - neither an override nor a
     * default chain for its type - fails scanning a valid candidate with a configuration error,
     * rather than silently loading it.
     */
    @Test
    fun `throws a configuration error when no security strategy at all is configured`(@TempDir tempDir: Path) {
        PluginScannerTestFixtures.writeJar(
            tempDir.resolve("plugin-a.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        assertThrows(IllegalStateException::class.java) {
            PluginScanner().scan(listOf(location))
        }
    }
}
