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
