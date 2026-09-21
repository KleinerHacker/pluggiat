package org.pcsoft.framework.pluggiat.scanner

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Scans a location's directory for ZIP files, unpacking each into a temporary directory and
 * treating the unpacked content like a [MultiJarWithOwnFolderScanStrategy] folder candidate.
 *
 * The temporary directory and all files extracted into it are registered via [java.io.File.deleteOnExit];
 * no further manual cleanup is required, though the registrations only take effect on JVM shutdown.
 */
class ZipJarScanStrategy : PluginScanStrategy {
    override fun scan(location: PluginLocation): List<PluginScanResult> {
        val zipPaths = Files.newDirectoryStream(location.path, "*.zip").use { it.toList() }
        return zipPaths.map { zipPath -> scanZip(location, zipPath) }
    }

    private fun scanZip(location: PluginLocation, zipPath: Path): PluginScanResult {
        val tempDir = Files.createTempDirectory("pluggiat-plugin-")
        unpack(zipPath, tempDir)
        registerDeleteOnExitRecursively(tempDir)
        return PluginManifestLookup.scanFolder(location, tempDir)
    }

    private fun unpack(zipPath: Path, targetDir: Path) {
        ZipInputStream(Files.newInputStream(zipPath)).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val entryPath = targetDir.resolve(entry.name).normalize()
                require(entryPath.startsWith(targetDir)) { "Zip entry escapes the target directory: '${entry.name}'" }

                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.newOutputStream(entryPath).use { output -> zipStream.copyTo(output) }
                }

                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
    }

    /**
     * Registers [root] and every file/directory created inside it for [java.io.File.deleteOnExit],
     * shallowest first, so that the resulting reverse deletion order removes children before their
     * parent directory.
     */
    private fun registerDeleteOnExitRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted().forEach { it.toFile().deleteOnExit() }
        }
    }
}
