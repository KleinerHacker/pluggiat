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

import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.pcsoft.framework.pluggiat.manifest.ManifestParser
import org.pcsoft.framework.pluggiat.manifest.ManifestValidationException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarInputStream

/**
 * Shared manifest-lookup logic used by the [PluginScanStrategy] implementations.
 *
 * Every lookup here follows the same two rules for choosing *the* manifest of a JAR, and it is the same
 * choice [org.pcsoft.framework.pluggiat.classloader.jar.resolveJarEntries] makes for any other entry:
 *
 * 1. among the possible manifest *names*, the first of [ManifestParser.MANIFEST_FILE_NAMES] that is
 *    present wins;
 * 2. among several entries carrying that *same* name, the **last** one wins - matching
 *    `java.util.zip.ZipFile`'s own duplicate-name behaviour.
 *
 * Both rules exist to keep the scanner, the signature verification and the class loader from ever
 * disagreeing about which bytes are the manifest. A JAR may legally contain the same entry name twice,
 * and a scanner that read the *first* `plugin.yml` while everything downstream used the *last* one
 * would let a candidate present a harmless manifest for inspection and a different one for loading -
 * different id, different dependencies, different extension implementations than were checked.
 */
internal object PluginManifestLookup {

    /**
     * Returns the name of the manifest entry inside [jarFile], or `null` if [jarFile] contains
     * none of [ManifestParser.MANIFEST_FILE_NAMES] - name priority as described for this object.
     * Duplicate entries of the chosen name need no handling here: [JarFile.getJarEntry] already resolves
     * a duplicate name to its last occurrence, which is exactly the rule [scanJarStream] implements by
     * hand for the streaming case.
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
            val entry = requireNotNull(jarFile.getJarEntry(entryName))
            toResult(location, jarPath) { jarFile.getInputStream(entry) }
        }

    /**
     * Scans [folder] as a single plugin candidate consisting of all JARs directly inside it,
     * identifying the manifest JAR among them by the presence of a manifest entry.
     */
    fun scanFolder(location: PluginLocation, folder: Path): PluginScanResult {
        val jarPaths = Files.newDirectoryStream(folder, "*.jar").use { it.toList() }
        return scanJars(location, jarPaths, folder, "folder '$folder'")
    }

    /**
     * Scans the JARs found directly inside [zipRoot] - the root [Path] of a ZIP file mounted as
     * its own [java.nio.file.FileSystem] (see [java.nio.file.FileSystems.newFileSystem]) - as a
     * single plugin candidate, identifying the manifest JAR among them by the presence of a
     * manifest entry. [resultPath] is the result's reported candidate path, i.e. the real ZIP file
     * on disk, since [zipRoot] itself only exists inside the temporary mounted filesystem.
     */
    fun scanZipFileSystem(location: PluginLocation, zipRoot: Path, resultPath: Path): PluginScanResult {
        val jarPaths = Files.newDirectoryStream(zipRoot, "*.jar").use { it.toList() }
        return scanJars(location, jarPaths, resultPath, "ZIP '$resultPath'")
    }

    /**
     * Shared implementation behind [scanFolder] and [scanZipFileSystem]: both scan a set of `*.jar`
     * [Path]s for the one among them containing a manifest entry, reporting [resultPath] as the
     * candidate's path either way. JARs are read as a stream rather than via [JarFile], since a
     * mounted ZIP filesystem's entries are not backed by a real [java.io.File].
     */
    private fun scanJars(location: PluginLocation, jarPaths: List<Path>, resultPath: Path, sourceDescription: String): PluginScanResult {
        if (jarPaths.isEmpty()) {
            return PluginScanResult(
                location, resultPath, null, PluginScanStatus.MANIFEST_NOT_FOUND,
                "No JAR files found in $sourceDescription",
            )
        }

        for (jarPath in jarPaths) {
            Files.newInputStream(jarPath).use { input ->
                val result = scanJarStream(location, resultPath, input)
                if (result != null) return result
            }
        }

        return PluginScanResult(
            location, resultPath, null, PluginScanStatus.MANIFEST_NOT_FOUND,
            "No manifest JAR found in $sourceDescription",
        )
    }

    /**
     * Streams [input] as a JAR, returning a [PluginScanResult] if it contains a manifest entry, or
     * `null` if it does not (so the caller can move on to the next candidate JAR).
     *
     * The stream is read to the end instead of stopping at the first manifest entry, because the entry
     * that counts is the last one of its name (see this object's documentation). Each manifest entry is
     * read only up to [PluginResourceLimits.MAX_MANIFEST_SIZE_BYTES] plus one byte: that one extra byte
     * is what lets [ManifestParser] recognize an oversized manifest and reject the candidate, without
     * this lookup ever holding more than the limit in memory.
     */
    private fun scanJarStream(location: PluginLocation, resultPath: Path, input: InputStream): PluginScanResult? =
        JarInputStream(input).use { jarStream ->
            val manifestsByName = linkedMapOf<String, ByteArray>()
            var entry = jarStream.nextJarEntry
            while (entry != null) {
                if (entry.name in ManifestParser.MANIFEST_FILE_NAMES) {
                    // SECURITY: overwrites a previous entry of the same name on purpose: last one wins, exactly
                    // SECURITY: as resolveJarEntries and ZipFile do, so scanner, verifier and class loader all
                    // SECURITY: read the same manifest bytes.
                    // SECURITY: capped read: one byte over the limit is enough for the parser to reject it.
                    manifestsByName[entry.name] = jarStream.readNBytes((PluginResourceLimits.MAX_MANIFEST_SIZE_BYTES + 1).toInt())
                }
                jarStream.closeEntry()
                entry = jarStream.nextJarEntry
            }

            // SECURITY: name priority comes from the framework's own fixed list, never from the archive's
            // SECURITY: entry order, which a candidate controls.
            val chosenName = ManifestParser.MANIFEST_FILE_NAMES.firstOrNull { it in manifestsByName } ?: return@use null
            toResult(location, resultPath) { ByteArrayInputStream(manifestsByName.getValue(chosenName)) }
        }

    private fun toResult(location: PluginLocation, candidatePath: Path, openManifestStream: () -> InputStream): PluginScanResult =
        try {
            val manifest = openManifestStream().use { ManifestParser.parse(it) }
            PluginScanResult(location, candidatePath, manifest, PluginScanStatus.LOADED)
        } catch (e: ManifestValidationException) {
            PluginScanResult(location, candidatePath, null, PluginScanStatus.MANIFEST_INVALID, e.message)
        }
}
