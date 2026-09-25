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

/**
 * Scans a location's directory for plugin subfolders, treating each subfolder's collection of
 * JARs as one plugin candidate. The manifest JAR among them is identified by the presence of a
 * manifest entry (`META-INF/plugin.yml`/`plugin.yaml`).
 */
class MultiJarWithOwnFolderScanStrategy : PluginScanStrategy {
    override fun scan(location: PluginLocation): List<PluginScanResult> {
        val subfolders = Files.newDirectoryStream(location.path) { Files.isDirectory(it) }.use { it.toList() }
        return subfolders.map { folder -> PluginManifestLookup.scanFolder(location, folder) }
    }
}
