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

package org.pcsoft.framework.pluggiat.sandbox.process

import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Resolves the JAR files a process-isolated plugin's subprocess is started with.
 *
 * Two sources, in order of preference:
 *
 * * [jarsFor] with the plugin's already-pinned [PinnedPluginContent] - the exact bytes that were
 *   security-checked, written once into the subprocess's own temporary working directory. This closes
 *   the check-to-load TOCTOU window for process isolation as well: the subprocess loads what was
 *   checked, not whatever is on the plugin's path by the time it starts.
 * * [jarsFor] with the plugin's [Path] - the fall-back for a plugin whose bytes were never pinned
 *   (e.g. a candidate loaded outside the regular scan path). This re-reads from disk and therefore
 *   keeps that window open.
 */
internal object PluginProcessClasspath {
    fun jarsFor(path: Path): List<Path> = when {
        Files.isDirectory(path) -> jarsIn(path)
        isMountedZip(path) -> error(mountedZipMessage(path))

        else -> listOf(path)
    }

    /**
     * Materializes [content] as real JAR files inside [targetDirectory] (the subprocess's working
     * directory) and returns them in deterministic order - a subprocess needs a file-system classpath,
     * so the pinned bytes have to be written somewhere, and writing them into a directory only this
     * subprocess uses keeps them out of reach of whoever might tamper with the plugin's original
     * location.
     *
     * @param path the plugin's original location, used only to reject a mounted ZIP candidate with the
     * same message as the path-based [jarsFor]
     */
    fun jarsFor(content: PinnedPluginContent, path: Path, targetDirectory: Path): List<Path> {
        if (isMountedZip(path)) error(mountedZipMessage(path))
        return when (content) {
            is PinnedPluginContent.Single -> listOf(write(targetDirectory.resolve(SINGLE_JAR_FILE_NAME), content.bytes))
            is PinnedPluginContent.Multi -> content.filesByName.entries
                .sortedBy { it.key }
                .map { (name, bytes) -> write(targetDirectory.resolve(safeFileName(name)), bytes) }
        }
    }

    private fun jarsIn(folder: Path): List<Path> =
        Files.newDirectoryStream(folder, "*.jar").use { it.toList() }

    private fun isMountedZip(path: Path): Boolean = path.toString().endsWith(".zip")

    private fun mountedZipMessage(path: Path): String =
        "Process-isolated plugins cannot be loaded from a mounted ZIP_JAR location ('$path') - " +
            "the subprocess needs a real file system path for its own classpath; use a plain JAR or folder location instead"

    /**
     * The bare file name of [name], with any directory part discarded. A pinned name comes from a
     * directory listing and is a plain file name already, but resolving an unvalidated name against the
     * working directory is exactly how a `../` component would end up writing outside it - so the name
     * is reduced to its last element before it is used as a path.
     */
    private fun safeFileName(name: String): String =
        Path.of(name.replace('\\', '/')).fileName?.toString()
            ?: error("Pinned plugin content contains an unusable file name: '$name'")

    private fun write(target: Path, bytes: ByteArray): Path =
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    /** File name the single-file pinned content of a `SINGLE_JAR` candidate is written under. */
    private const val SINGLE_JAR_FILE_NAME = "pluggiat-pinned-plugin.jar"
}
