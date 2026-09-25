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

import java.nio.file.Files
import java.nio.file.Path

/**
 * The exact, once-read bytes of a plugin candidate, pinned at the moment [PluginScanner] applies its
 * security check so that the very same bytes are later used by
 * `org.pcsoft.framework.pluggiat.classloader.PluginLoader` to load the plugin - closing the
 * check-to-load TOCTOU window where the candidate could otherwise be swapped on disk in between.
 */
sealed interface PinnedPluginContent {

    /**
     * A candidate backed by a single file (a `SINGLE_JAR`/`ZIP_JAR` candidate).
     *
     * @property bytes the candidate file's exact bytes, as read once at pinning time
     */
    data class Single(val bytes: ByteArray) : PinnedPluginContent {
        override fun equals(other: Any?): Boolean = this === other || (other is Single && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * A candidate backed by several files (a `MULTI_JAR_WITH_OWN_FOLDER` candidate), keyed by file
     * name.
     *
     * @property filesByName each `*.jar` file's exact bytes, as read once at pinning time, keyed by
     * its file name
     */
    data class Multi(val filesByName: Map<String, ByteArray>) : PinnedPluginContent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Multi && filesByName.keys == other.filesByName.keys &&
                filesByName.all { (name, bytes) -> other.filesByName[name]?.contentEquals(bytes) == true })
        override fun hashCode(): Int = filesByName.entries.sumOf { (name, bytes) -> name.hashCode() * 31 + bytes.contentHashCode() }
    }
}

/**
 * Reads a plugin candidate at [path] into a [PinnedPluginContent], once, for pinning by
 * [PluginScanner] and reuse by `org.pcsoft.framework.pluggiat.PluginManager.reactivate`. For a
 * directory candidate (`MULTI_JAR_WITH_OWN_FOLDER`), pins every `*.jar` file directly inside it, in
 * deterministic (sorted) file name order; otherwise pins the single file's bytes as-is (a `.zip`
 * candidate is pinned as one opaque file, consistent with it being signed/checksummed as a whole).
 */
internal object PinnedPluginContentReader {
    fun read(path: Path): PinnedPluginContent =
        if (Files.isDirectory(path)) {
            val files = Files.newDirectoryStream(path, "*.jar").use { it.toList() }.sortedBy { it.fileName.toString() }
            PinnedPluginContent.Multi(files.associate { it.fileName.toString() to Files.readAllBytes(it) })
        } else {
            PinnedPluginContent.Single(Files.readAllBytes(path))
        }
}
