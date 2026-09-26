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

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the JAR file(s) of a plugin candidate at [path] for the subprocess JVM's `-cp`, mirroring
 * `org.pcsoft.framework.pluggiat.classloader.PluginLoader`'s own directory/ZIP/single-JAR
 * auto-detection.
 *
 * Unlike [org.pcsoft.framework.pluggiat.classloader.PluginLoader], this always re-reads [path] from
 * disk rather than from an already security-pinned in-memory copy - a process-isolated plugin's
 * subprocess necessarily needs real files to point its own classpath at. This re-opens the
 * check-to-load TOCTOU window `org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent` otherwise
 * closes for in-VM loading; see the "Risks and Open Questions" section of the FP-002 feature plan
 * for this known, accepted limitation of IP-04.
 */
internal object PluginProcessClasspath {
    /** Resolves [path] to the list of JAR files a subprocess classpath for it must contain. */
    fun jarsFor(path: Path): List<Path> = when {
        Files.isDirectory(path) -> jarsIn(path)
        path.toString().endsWith(".zip") -> error(
            "Process-isolated plugins cannot be loaded from a mounted ZIP_JAR location ('$path') - " +
                "the subprocess needs a real file system path for its own classpath; use a plain JAR or folder location instead",
        )

        else -> listOf(path)
    }

    private fun jarsIn(folder: Path): List<Path> =
        Files.newDirectoryStream(folder, "*.jar").use { it.toList() }
}
