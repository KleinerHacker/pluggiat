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

package org.pcsoft.framework.pluggiat.classloader.jar

import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PluginContentLimitExceededException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Resolves [content] into a single, flat map of ZIP/JAR entry name to entry bytes.
 *
 * This is the one shared entry-resolution function used both by
 * `org.pcsoft.framework.pluggiat.scanner.PluginManifestLookup` (to locate the manifest entry
 * of a pinned candidate) and by
 * `org.pcsoft.framework.pluggiat.classloader.PinnedPluginClassLoader` (to load classes/resources) -
 * so both sides interpret duplicate ZIP entry names identically and can never diverge on which entry
 * "wins" ("verify one entry, load another").
 *
 * - [PinnedPluginContent.Single] is read as one ZIP/JAR. An entry whose name ends in `.jar` is
 *   itself unpacked and merged in (recursively), so a `ZIP_JAR` candidate - whose outer `.zip` simply
 *   contains further JARs, see `org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy` - resolves
 *   directly to the class/resource entries inside those inner JARs.
 *
 * A duplicate entry name (within one ZIP, or across merged inner JARs) resolves to its *last* occurrence,
 * matching the JDK's own `java.util.zip.ZipFile` "last entry wins" behavior for duplicate names.
 *
 * Unpacking is bounded by [PluginResourceLimits]: per entry, over the whole candidate, and in nesting
 * depth. All three bounds exist because this function unpacks attacker-controlled archives *before*
 * any security strategy has accepted the candidate - a few hundred kilobytes of ZIP can otherwise
 * expand to gigabytes (a "zip bomb"), and a JAR that contains itself would recurse until the stack or
 * the heap gives out.
 *
 * @throws PluginContentLimitExceededException if one of those bounds is exceeded
 */
fun resolveJarEntries(content: PinnedPluginContent): Map<String, ByteArray> {
    // SECURITY: one budget for the whole candidate, not per archive - the bound that matters is how much
    // SECURITY: a single candidate can make the host allocate in total, however it spreads that over files.
    val budget = UnpackBudget()
    return when (content) {
        is PinnedPluginContent.Single -> resolveEntriesFromZipBytes(content.bytes, depth = 0, budget = budget)
    }
}

/**
 * Running total of everything unpacked for one candidate, see [resolveJarEntries].
 */
private class UnpackBudget {
    private var unpackedBytes = 0L

    fun consume(bytes: Long, entryName: String) {
        unpackedBytes += bytes
        if (unpackedBytes > PluginResourceLimits.MAX_UNPACKED_TOTAL_SIZE_BYTES) {
            throw PluginContentLimitExceededException(
                "Plugin candidate unpacks to more than the maximum of " +
                    "${PluginResourceLimits.MAX_UNPACKED_TOTAL_SIZE_BYTES} bytes (while reading entry '$entryName')",
            )
        }
    }
}

private fun resolveEntriesFromZipBytes(bytes: ByteArray, depth: Int, budget: UnpackBudget): Map<String, ByteArray> {
    // SECURITY: checked on entry rather than before recursing, so the outermost archive counts too and a
    // SECURITY: self-containing JAR terminates here instead of exhausting the stack.
    if (depth > PluginResourceLimits.MAX_NESTING_DEPTH) {
        throw PluginContentLimitExceededException(
            "Plugin candidate nests archives deeper than the maximum of ${PluginResourceLimits.MAX_NESTING_DEPTH} levels",
        )
    }

    val direct = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zipStream ->
        var entry = zipStream.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                // SECURITY: the size in the ZIP header is written by whoever built the archive, so it is
                // SECURITY: only a cheap early reject (-1 when absent); the authoritative bound is enforced
                // SECURITY: in readBoundedEntry while the bytes actually arrive.
                val declaredSize = entry.size
                if (declaredSize > PluginResourceLimits.MAX_UNPACKED_ENTRY_SIZE_BYTES) {
                    throw PluginContentLimitExceededException(
                        "Entry '${entry.name}' of a plugin candidate declares $declaredSize bytes and exceeds the " +
                            "maximum entry size of ${PluginResourceLimits.MAX_UNPACKED_ENTRY_SIZE_BYTES} bytes",
                    )
                }
                direct[entry.name] = readBoundedEntry(zipStream, entry.name, budget)
            }
            entry = zipStream.nextEntry
        }
    }

    val resolved = linkedMapOf<String, ByteArray>()
    for ((name, entryBytes) in direct) {
        if (name.endsWith(".jar")) {
            resolved.putAll(resolveEntriesFromZipBytes(entryBytes, depth + 1, budget))
        } else {
            resolved[name] = entryBytes
        }
    }
    return resolved
}

/**
 * Reads the current entry of [zipStream] in chunks, enforcing both the per-entry limit and the
 * candidate-wide [budget] as the bytes arrive.
 *
 * Deliberately not `zipStream.readBytes()`: that would have to finish decompressing the whole entry -
 * however large it really turns out to be - before any limit could be looked at, which is exactly the
 * allocation a zip bomb is after.
 */
private fun readBoundedEntry(zipStream: ZipInputStream, entryName: String, budget: UnpackBudget): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_READ_CHUNK_SIZE)
    while (true) {
        // SECURITY: chunk-wise, so the limits are checked before the next chunk is allocated.
        val read = zipStream.read(buffer)
        if (read < 0) break
        if (out.size().toLong() + read > PluginResourceLimits.MAX_UNPACKED_ENTRY_SIZE_BYTES) {
            throw PluginContentLimitExceededException(
                "Entry '$entryName' of a plugin candidate unpacks to more than the maximum entry size of " +
                    "${PluginResourceLimits.MAX_UNPACKED_ENTRY_SIZE_BYTES} bytes",
            )
        }
        budget.consume(read.toLong(), entryName)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DEFAULT_READ_CHUNK_SIZE = 8 * 1024
