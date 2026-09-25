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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scans a location's directory for ZIP files, mounting each one as its own [java.nio.file.FileSystem]
 * (see [FileSystems.newFileSystem]) and treating its content like a [MultiJarWithOwnFolderScanStrategy]
 * folder candidate, without ever unpacking the ZIP's content onto disk.
 *
 * The resulting [PluginScanResult.path] is the real `.zip` file on disk, not a path inside the
 * mounted filesystem, since the latter only exists for the duration of a single scan call.
 */
class ZipJarScanStrategy : PluginScanStrategy {
    override fun scan(location: PluginLocation): List<PluginScanResult> {
        val zipPaths = Files.newDirectoryStream(location.path, "*.zip").use { it.toList() }
        return zipPaths.map { zipPath -> scanZip(location, zipPath) }
    }

    private fun scanZip(location: PluginLocation, zipPath: Path): PluginScanResult =
        FileSystems.newFileSystem(zipPath).use { zipFileSystem ->
            val root = zipFileSystem.rootDirectories.first()
            PluginManifestLookup.scanZipFileSystem(location, root, zipPath)
        }
}
