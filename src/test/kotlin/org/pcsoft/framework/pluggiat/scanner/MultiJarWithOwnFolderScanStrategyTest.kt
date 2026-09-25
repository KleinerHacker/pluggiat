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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MultiJarWithOwnFolderScanStrategyTest {

    /**
     * Use case: a plugin's own subfolder contains several JARs; the one carrying the manifest is
     * identified among them and the subfolder is reported as one loaded plugin candidate.
     */
    @Test
    fun `scans a plugin subfolder with a manifest JAR among several JARs as loaded`(@TempDir tempDir: Path) {
        val pluginFolder = Files.createDirectory(tempDir.resolve("plugin-a"))
        PluginScannerTestFixtures.writeJar(
            pluginFolder.resolve("plugin-a-manifest.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        PluginScannerTestFixtures.writeJar(
            pluginFolder.resolve("plugin-a-lib.jar"),
            mapOf("com/example/Lib.class" to "not a real class"),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, MultiJarWithOwnFolderScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        assertEquals(PluginScanStatus.LOADED, results.single().status)
        assertEquals("plugin-a", results.single().manifest?.id)
    }

    /**
     * Use case: a plugin's own subfolder contains JARs but none of them carries a manifest; the
     * subfolder is reported as an invalid candidate with reason `MANIFEST_NOT_FOUND`.
     */
    @Test
    fun `scans a plugin subfolder without a manifest JAR as invalid`(@TempDir tempDir: Path) {
        val pluginFolder = Files.createDirectory(tempDir.resolve("plugin-b"))
        PluginScannerTestFixtures.writeJar(
            pluginFolder.resolve("plugin-b-lib.jar"),
            mapOf("com/example/Lib.class" to "not a real class"),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, MultiJarWithOwnFolderScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        assertEquals(PluginScanStatus.MANIFEST_NOT_FOUND, results.single().status)
    }
}
