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

package org.pcsoft.framework.pluggiat.classloader

import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import java.nio.file.FileSystem

/**
 * A single successfully loaded plugin.
 *
 * @property pluginId id of the loaded plugin, from [manifest]
 * @property manifest the plugin's manifest
 * @property classLoader the plugin's isolated class loader
 * @property mountedFileSystem the ZIP file system mounted for a `ZIP_JAR` plugin, kept open for as
 * long as [classLoader] is in use; `null` for the other load modes. Closed by [close].
 */
data class LoadedPlugin(
    val pluginId: String,
    val manifest: PluginManifest,
    val classLoader: PluginClassLoader,
    private val mountedFileSystem: FileSystem? = null,
) : AutoCloseable {

    /**
     * Discards this plugin's isolated [classLoader] and closes [mountedFileSystem] (if any), as the
     * fixed final step of every deactivation (disable/unload/forced UNLOAD). Reactivation afterward
     * requires a full reload - a discarded [classLoader] can never be resumed.
     */
    override fun close() {
        classLoader.close()
        mountedFileSystem?.close()
    }
}
