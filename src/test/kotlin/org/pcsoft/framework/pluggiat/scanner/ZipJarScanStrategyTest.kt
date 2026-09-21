package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
     * Use case: a ZIP containing a manifest JAR is unpacked into a temporary directory and reported
     * as one loaded plugin candidate.
     */
    @Test
    fun `scans a ZIP containing a manifest JAR as loaded and unpacks it into a temp directory`(@TempDir tempDir: Path) {
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
        assertTrue(Files.isDirectory(result.path))
        assertTrue(Files.exists(result.path.resolve("plugin-a.jar")))
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
        assertEquals(PluginScanStatus.MANIFEST_NOT_FOUND, results.single().status)
    }

    /**
     * Use case: the temporary directory an unpacked ZIP is extracted into, and every file/directory
     * extracted below it, is registered for cleanup via [java.io.File.deleteOnExit].
     */
    @Test
    fun `registers the unpacked temp directory and its content for deleteOnExit`(@TempDir tempDir: Path) {
        val zipPath = tempDir.resolve("plugin-a.zip")
        writeZipWithBinaryEntries(
            zipPath,
            mapOf("plugin-a.jar" to jarBytes(mapOf("META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a")))),
        )
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, ZipJarScanStrategy())

        val result = location.scanStrategy.scan(location).single()

        val registeredPaths = deleteOnExitRegisteredPaths()
        assertTrue(registeredPaths.contains(result.path.toString()))
        assertTrue(registeredPaths.contains(result.path.resolve("plugin-a.jar").toString()))
    }

    private fun deleteOnExitRegisteredPaths(): Set<String> {
        val hookClass = Class.forName("java.io.DeleteOnExitHook")
        val filesField = hookClass.getDeclaredField("files")
        filesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (filesField.get(null) as Set<String>).toSet()
    }
}
