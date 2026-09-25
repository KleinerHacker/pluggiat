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

import java.net.URL
import java.net.URLClassLoader

/**
 * Isolated, parent-last class loader for a single loaded plugin.
 *
 * Class resolution order: platform classes (`java.*`/`javax.*`, always delegated to the JDK
 * platform class loader regardless of [sdkWhitelist]), then [sdkWhitelist]-matched classes
 * delegated to [hostClassLoader], then the plugin's own JARs ([urls]), then
 * [dependencyClassLoaders] in declaration order. A plugin can therefore never see host-internal
 * classes outside the whitelist, and never sees another plugin's classes unless that plugin's
 * class loader was explicitly passed in as a dependency.
 *
 * @param urls the plugin's own JAR(s) to load classes/resources from
 * @property hostClassLoader the host application's own class loader, consulted for
 * [sdkWhitelist]-matched classes
 * @property sdkWhitelist packages of the host's own SDK exposed to this plugin
 * @property dependencyClassLoaders class loaders of this plugin's already-loaded dependencies, in
 * declaration order
 */
class PluginClassLoader(
    urls: Array<URL>,
    private val hostClassLoader: ClassLoader,
    private val sdkWhitelist: List<SdkWhitelistEntry>,
    private val dependencyClassLoaders: List<PluginClassLoader>,
) : URLClassLoader(urls, null) {

    private val platformClassLoader: ClassLoader = getPlatformClassLoader()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it.also { if (resolve) resolveClass(it) } }

            if (isPlatformClass(name)) {
                runCatching { platformClassLoader.loadClass(name) }.getOrNull()
                    ?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            if (isWhitelisted(name)) {
                runCatching { hostClassLoader.loadClass(name) }.getOrNull()
                    ?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            runCatching { findClass(name) }.getOrNull()
                ?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }

            for (dependency in dependencyClassLoaders) {
                runCatching { dependency.loadClass(name) }.getOrNull()
                    ?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            throw ClassNotFoundException(name)
        }
    }

    private fun isPlatformClass(name: String): Boolean =
        name.startsWith("java.") || name.startsWith("javax.")

    private fun isWhitelisted(name: String): Boolean =
        sdkWhitelist.any { entry ->
            if (entry.recursive) {
                name == entry.packageName || name.startsWith("${entry.packageName}.")
            } else {
                name.substringBeforeLast('.', "") == entry.packageName
            }
        }
}
