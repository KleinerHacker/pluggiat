package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipJarScanStrategyTest {

    private fun jarBytes(entries: Map<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        JarOutputStream(buffer).use { jarStream ->
            for ((name, content) in entries) {
                jarStream.putNextEntry(JarEntry(name))
                jarStream.write(content.toByteArray(Charsets.UTF_8))
                jarStream.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Writes a ZIP file at [zipPath] containing one binary entry per key/value pair in [entries]
     * (entry name to raw content bytes), for fixtures embedding a JAR as a ZIP entry.
     */
    private fun writeZipWithBinaryEntries(zipPath: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zipStream ->
            for ((name, content) in entries) {
                zipStream.putNextEntry(ZipEntry(name))
                zipStream.write(content)
                zipStream.closeEntry()
            }
        }
    }

    /**
     * Use case: a ZIP containing a manifest JAR is scanned via its mounted ZIP filesystem, without
     * being unpacked onto disk, and reported as one loaded plugin candidate whose path is the ZIP
     * file itself.
     */
    @Test
    fun `scans a ZIP containing a manifest JAR as loaded without unpacking it`(@TempDir tempDir: Path) {
        val zipPath = tempDir.resolve("plugin-a.zip")
        writeZipWithBinaryEntries(
            zipPath,
            mapOf("plugin-a.jar" to jarBytes(mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, ZipJarScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(PluginScanStatus.LOADED, result.status)
        assertEquals("plugin-a", result.manifest?.id)
        assertEquals(zipPath, result.path)
    }

    /**
     * Use case: a ZIP containing only unrelated files (no manifest JAR) is reported as an invalid
     * candidate with reason `MANIFEST_NOT_FOUND`.
     */
    @Test
    fun `scans a ZIP without a manifest JAR as invalid`(@TempDir tempDir: Path) {
        val zipPath = tempDir.resolve("plugin-b.zip")
        PluginScannerTestFixtures.writeZip(zipPath, mapOf("readme.txt" to "no plugin here"))
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, ZipJarScanStrategy())

        val results = location.scanStrategy.scan(location)

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(PluginScanStatus.MANIFEST_NOT_FOUND, result.status)
        assertNull(result.manifest)
    }
}
