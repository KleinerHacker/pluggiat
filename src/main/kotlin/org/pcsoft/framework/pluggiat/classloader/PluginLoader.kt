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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates isolated [PluginClassLoader]s for scanned plugin candidates.
 *
 * Deliberately unaware of [org.pcsoft.framework.pluggiat.scanner.PluginScanResult] or any other
 * scanner/security type - it only needs a candidate's own [Path] (single JAR, own folder, or ZIP
 * file, auto-detected) and its already-parsed [PluginManifest]. It performs no security check of
 * its own and is unconditionally callable regardless of any prior security outcome - deciding
 * *whether* loading a given plugin is warranted, and logging that decision, is entirely up to the
 * caller.
 *
 * @property hostClassLoader the host application's own class loader, consulted for
 * whitelisted/platform classes
 * @property sdkWhitelist packages of the host's own SDK exposed to plugins
 */
class PluginLoader(
    private val hostClassLoader: ClassLoader = PluginLoader::class.java.classLoader,
    private val sdkWhitelist: List<SdkWhitelistEntry> = emptyList(),
) {
    /**
     * Loads the plugin candidate at [path] with the already-parsed [manifest].
     *
     * [dependencies] must contain every already-loaded plugin [manifest] declares a dependency on,
     * keyed by plugin id; a declared `required` dependency missing from [dependencies] invalidates
     * the plugin, a missing `optional` one is silently skipped.
     */
    fun load(
        path: Path,
        manifest: PluginManifest,
        dependencies: Map<String, LoadedPlugin> = emptyMap(),
    ): PluginLoadResult {
        val dependencyClassLoaders = mutableListOf<PluginClassLoader>()
        for (dependency in manifest.dependencies) {
            val loaded = dependencies[dependency.id]
            if (loaded == null) {
                if (dependency.required) {
                    return PluginLoadResult.Invalid(manifest.id, "Missing required dependency '${dependency.id}'")
                }
                continue
            }
            dependencyClassLoaders.add(loaded.classLoader)
        }

        val (classLoader, mountedFileSystem) = createClassLoader(path, dependencyClassLoaders)
        return PluginLoadResult.Loaded(LoadedPlugin(manifest.id, manifest, classLoader, mountedFileSystem))
    }

    private fun createClassLoader(path: Path, dependencyClassLoaders: List<PluginClassLoader>): Pair<PluginClassLoader, FileSystem?> =
        when {
            Files.isDirectory(path) -> classLoaderFromJars(jarsIn(path), dependencyClassLoaders) to null
            path.toString().endsWith(".zip") -> {
                val fileSystem = FileSystems.newFileSystem(path)
                val root = fileSystem.rootDirectories.first()
                classLoaderFromJars(jarsIn(root), dependencyClassLoaders) to fileSystem
            }

            else -> classLoaderFromJars(listOf(path), dependencyClassLoaders) to null
        }

    private fun jarsIn(folder: Path): List<Path> =
        Files.newDirectoryStream(folder, "*.jar").use { it.toList() }

    private fun classLoaderFromJars(jarPaths: List<Path>, dependencyClassLoaders: List<PluginClassLoader>): PluginClassLoader {
        val urls = jarPaths.map { it.toUri().toURL() }.toTypedArray()
        return PluginClassLoader(urls, hostClassLoader, sdkWhitelist, dependencyClassLoaders)
    }
}
