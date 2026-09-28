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
     * A candidate backed by a single file (a single JAR or a ZIP of JARs).
     *
     * @property bytes the candidate file's exact bytes, as read once at pinning time
     */
    data class Single(val bytes: ByteArray) : PinnedPluginContent {
        override fun equals(other: Any?): Boolean = this === other || (other is Single && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/**
 * Reads a plugin candidate at [path] into a [PinnedPluginContent], once, for pinning by
 * [PluginScanner] and reuse by `org.pcsoft.framework.pluggiat.PluginManager.reactivate`. The single
 * file's bytes are pinned as-is (a `.zip` candidate is pinned as one opaque file, consistent with it
 * being signed/checksummed as a whole).
 *
 * The file's size is checked against [PluginResourceLimits] *before* it is read: this is the first
 * place the framework touches plugin-controlled data, and `readAllBytes` on an arbitrarily large file
 * would end the host JVM with an `OutOfMemoryError` before any security strategy ever ran.
 */
internal object PinnedPluginContentReader {

    /**
     * @throws PluginContentLimitExceededException if the candidate file exceeds
     * [PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES]
     */
    fun read(path: Path): PinnedPluginContent {
        // SECURITY: the size is checked before the first byte is read, so an oversized file is rejected
        // SECURITY: without ever being in memory.
        requireReadableSize(path)
        return PinnedPluginContent.Single(Files.readAllBytes(path))
    }

    /**
     * Verifies [file] is within [PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES]. Queried via
     * [Files.size] rather than by reading and measuring, so an oversized file is rejected without ever
     * being in memory.
     */
    private fun requireReadableSize(file: Path) {
        val size = Files.size(file)
        if (size > PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES) {
            throw PluginContentLimitExceededException(
                "Plugin candidate file '$file' is $size bytes and exceeds the maximum candidate file size of " +
                    "${PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES} bytes",
            )
        }
    }
}
