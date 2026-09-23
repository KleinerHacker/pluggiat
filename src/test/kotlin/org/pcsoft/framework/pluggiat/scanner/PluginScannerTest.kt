package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Files
import java.nio.file.Path

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

        val multiJarLocationDir = Files.createDirectory(tempDir.resolve("multi-jar-location"))
        val pluginBFolder = Files.createDirectory(multiJarLocationDir.resolve("plugin-b"))
        PluginScannerTestFixtures.writeJar(
            pluginBFolder.resolve("plugin-b.jar"),
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.invalidManifestYaml("plugin-b")),
        )
        val multiJarLocation = PluginLocation(multiJarLocationDir, PluginLocationType.EXTERNAL, MultiJarWithOwnFolderScanStrategy())

        val results = PluginScanner(insecureDefaults).scan(listOf(singleJarLocation, multiJarLocation))

        assertEquals(2, results.size)
        assertTrue(results.any { it.location == singleJarLocation && it.status == PluginScanStatus.LOADED })
        assertTrue(results.any { it.location == multiJarLocation && it.status == PluginScanStatus.MANIFEST_INVALID })
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
