package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.PluginManifest

class LoadedPluginTest {

    private fun manifest(id: String) =
        PluginManifest(id = id, name = id, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

    /**
     * Use case: [LoadedPlugin.close] discards the plugin's isolated class loader - a subsequent
     * resource lookup through it resolves to nothing instead of continuing to serve resources.
     */
    @Test
    fun `close discards the plugin class loader`() {
        val classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList())
        val loadedPlugin = LoadedPlugin("plugin-a", manifest("plugin-a"), classLoader)

        loadedPlugin.close()

        assertNull(classLoader.findResource("does/not/matter.txt"))
    }

    /**
     * Use case: [LoadedPlugin.close] is safe to call even without a mounted ZIP file system.
     */
    @Test
    fun `close does not throw when there is no mounted file system`() {
        val classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList())
        val loadedPlugin = LoadedPlugin("plugin-a", manifest("plugin-a"), classLoader, mountedFileSystem = null)

        loadedPlugin.close()
    }
}
