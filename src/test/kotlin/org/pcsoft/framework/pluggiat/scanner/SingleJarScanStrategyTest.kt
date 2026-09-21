package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SingleJarScanStrategyTest {

    /**
     * Use case: a standalone JAR file with a valid manifest under `META-INF` is scanned as a single
     * loaded plugin candidate.
     */
    @Test
    fun `scans a standalone JAR with a valid manifest as loaded`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(
            jarPath,
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        assertEquals(PluginScanStatus.LOADED, results.single().status)
        assertEquals("plugin-a", results.single().manifest?.id)
    }

    /**
     * Use case: a standalone JAR without any manifest entry is reported as an invalid candidate
     * with reason `MANIFEST_NOT_FOUND`.
     */
    @Test
    fun `scans a standalone JAR without a manifest as invalid`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-b.jar")
        PluginScannerTestFixtures.writeJar(jarPath, mapOf("com/example/Plugin.class" to "not a real class"))
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        assertEquals(PluginScanStatus.MANIFEST_NOT_FOUND, results.single().status)
        assertNull(results.single().manifest)
    }

    /**
     * Use case: a standalone JAR whose manifest fails schema validation is reported as an invalid
     * candidate with reason `MANIFEST_INVALID`.
     */
    @Test
    fun `scans a standalone JAR with an invalid manifest as invalid`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-c.jar")
        PluginScannerTestFixtures.writeJar(
            jarPath,
            mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.invalidManifestYaml("plugin-c")),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        assertEquals(PluginScanStatus.MANIFEST_INVALID, results.single().status)
    }
}
