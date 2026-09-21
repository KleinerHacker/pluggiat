package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.manifest.ManifestParser
import org.pcsoft.framework.pluggiat.manifest.ManifestValidationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Shared manifest-lookup logic used by the [PluginScanStrategy] implementations.
 */
internal object PluginManifestLookup {

    /**
     * Returns the name of the manifest entry inside [jarFile], or `null` if [jarFile] contains
     * none of [ManifestParser.MANIFEST_FILE_NAMES].
     */
    fun findManifestEntryName(jarFile: JarFile): String? =
        ManifestParser.MANIFEST_FILE_NAMES.firstOrNull { jarFile.getJarEntry(it) != null }

    /**
     * Scans [jarPath] itself as a single plugin candidate, looking for a manifest directly inside
     * this one JAR.
     */
    fun scanSingleJar(location: PluginLocation, jarPath: Path): PluginScanResult =
        JarFile(jarPath.toFile()).use { jarFile ->
            val entryName = findManifestEntryName(jarFile)
                ?: return PluginScanResult(
                    location, jarPath, null, PluginScanStatus.MANIFEST_NOT_FOUND,
                    "No manifest found in JAR '$jarPath'",
                )
            toResult(location, jarPath, jarFile, entryName)
        }

    /**
     * Scans [folder] as a single plugin candidate consisting of all JARs directly inside it,
     * identifying the manifest JAR among them by the presence of a manifest entry.
     */
    fun scanFolder(location: PluginLocation, folder: Path): PluginScanResult {
        val jarPaths = Files.newDirectoryStream(folder, "*.jar").use { it.toList() }
        if (jarPaths.isEmpty()) {
            return PluginScanResult(
                location, folder, null, PluginScanStatus.MANIFEST_NOT_FOUND,
                "No JAR files found in '$folder'",
            )
        }

        for (jarPath in jarPaths) {
            JarFile(jarPath.toFile()).use { jarFile ->
                val entryName = findManifestEntryName(jarFile) ?: return@use
                return toResult(location, folder, jarFile, entryName)
            }
        }

        return PluginScanResult(
            location, folder, null, PluginScanStatus.MANIFEST_NOT_FOUND,
            "No manifest JAR found in '$folder'",
        )
    }

    private fun toResult(location: PluginLocation, candidatePath: Path, jarFile: JarFile, entryName: String): PluginScanResult {
        val entry = requireNotNull(jarFile.getJarEntry(entryName))
        return try {
            val manifest = jarFile.getInputStream(entry).use { ManifestParser.parse(it) }
            PluginScanResult(location, candidatePath, manifest, PluginScanStatus.LOADED)
        } catch (e: ManifestValidationException) {
            PluginScanResult(location, candidatePath, null, PluginScanStatus.MANIFEST_INVALID, e.message)
        }
    }
}
