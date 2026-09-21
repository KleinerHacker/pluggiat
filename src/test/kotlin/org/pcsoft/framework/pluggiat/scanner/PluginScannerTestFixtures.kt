package org.pcsoft.framework.pluggiat.scanner

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds JAR/ZIP fixtures for the scanner tests at runtime instead of committing binary blobs.
 */
object PluginScannerTestFixtures {

    /**
     * A minimal but schema-valid manifest YAML for a plugin with the given [id].
     */
    fun validManifestYaml(id: String): String = """
        ${'$'}version: 1
        id: $id
        name: $id
        version: "1.0.0"
        minVersion: "1.0.0"
        icon: aWNvbg==
    """.trimIndent()

    /**
     * A manifest YAML that fails schema validation (missing the required `icon` field).
     */
    fun invalidManifestYaml(id: String): String = """
        ${'$'}version: 1
        id: $id
        name: $id
        version: "1.0.0"
        minVersion: "1.0.0"
    """.trimIndent()

    /**
     * Writes a JAR file at [path] containing one entry per key/value pair in [entries] (entry name
     * to its UTF-8 text content).
     */
    fun writeJar(path: Path, entries: Map<String, String>) {
        java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(path)).use { jarStream ->
            for ((name, content) in entries) {
                jarStream.putNextEntry(java.util.jar.JarEntry(name))
                jarStream.write(content.toByteArray(Charsets.UTF_8))
                jarStream.closeEntry()
            }
        }
    }

    /**
     * Writes a ZIP file at [path] containing one entry per key/value pair in [entries] (entry name
     * to its UTF-8 text content).
     */
    fun writeZip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(java.nio.file.Files.newOutputStream(path)).use { zipStream ->
            for ((name, content) in entries) {
                zipStream.putNextEntry(ZipEntry(name))
                zipStream.write(content.toByteArray(Charsets.UTF_8))
                zipStream.closeEntry()
            }
        }
    }
}
