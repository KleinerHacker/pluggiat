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

import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Resolves [content] into a single, flat map of ZIP/JAR entry name to entry bytes.
 *
 * This is the one shared entry-resolution function used both by
 * `org.pcsoft.framework.pluggiat.security.SignatureSecurityStrategy` (to locate the manifest entry
 * and checksum list of a pinned candidate) and by
 * `org.pcsoft.framework.pluggiat.classloader.PinnedPluginClassLoader` (to load classes/resources) -
 * so both sides interpret duplicate ZIP entry names identically and can never diverge on which entry
 * "wins" ("verify one entry, load another").
 *
 * - [PinnedPluginContent.Single] is read as one ZIP/JAR. An entry whose name ends in `.jar` is
 *   itself unpacked and merged in (recursively), so a `ZIP_JAR` candidate - whose outer `.zip` simply
 *   contains further JARs, see `org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy` - resolves
 *   directly to the class/resource entries inside those inner JARs.
 * - [PinnedPluginContent.Multi] is resolved file by file, in sorted file name order, and merged.
 *
 * A duplicate entry name (within one ZIP, or across merged files) resolves to its *last* occurrence,
 * matching the JDK's own `java.util.zip.ZipFile` "last entry wins" behavior for duplicate names.
 */
fun resolveJarEntries(content: PinnedPluginContent): Map<String, ByteArray> = when (content) {
    is PinnedPluginContent.Single -> resolveEntriesFromZipBytes(content.bytes)
    is PinnedPluginContent.Multi -> {
        val merged = linkedMapOf<String, ByteArray>()
        for (fileName in content.filesByName.keys.sorted()) {
            merged.putAll(resolveEntriesFromZipBytes(content.filesByName.getValue(fileName)))
        }
        merged
    }
}

private fun resolveEntriesFromZipBytes(bytes: ByteArray): Map<String, ByteArray> {
    val direct = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zipStream ->
        var entry = zipStream.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                direct[entry.name] = zipStream.readBytes()
            }
            entry = zipStream.nextEntry
        }
    }

    val resolved = linkedMapOf<String, ByteArray>()
    for ((name, entryBytes) in direct) {
        if (name.endsWith(".jar")) {
            resolved.putAll(resolveEntriesFromZipBytes(entryBytes))
        } else {
            resolved[name] = entryBytes
        }
    }
    return resolved
}
