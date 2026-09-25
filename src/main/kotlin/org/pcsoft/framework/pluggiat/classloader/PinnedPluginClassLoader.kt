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

import org.pcsoft.framework.pluggiat.classloader.jar.resolveJarEntries
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections

/**
 * A [PluginClassLoader] that resolves classes/resources exclusively from an already-pinned
 * [content] instead of re-reading JAR(s) from disk via [java.net.URLClassLoader]'s own URL
 * resolution.
 *
 * Used by [PluginLoader] for a candidate whose bytes were pinned by
 * `org.pcsoft.framework.pluggiat.scanner.PluginScanner` (or
 * `org.pcsoft.framework.pluggiat.PluginManager.reactivate`) at security-check time, so the classes
 * actually loaded are guaranteed to be exactly the bytes that were checked - the plugin cannot be
 * swapped on disk in between (see [PinnedPluginContent]). Class/resource lookup within [content]
 * uses the shared [resolveJarEntries] function, the same one
 * `org.pcsoft.framework.pluggiat.security.SignatureSecurityStrategy` uses to verify a candidate, so
 * both sides agree on which entry among duplicate ZIP entry names is authoritative.
 *
 * Inherits [PluginClassLoader]'s `loadClass` precedence (platform classes, then SDK whitelist via
 * the host class loader, then this class loader's own [findClass]/[findResource], then dependency
 * class loaders) unchanged - only where classes/resources ultimately come from differs.
 */
class PinnedPluginClassLoader(
    private val content: PinnedPluginContent,
    hostClassLoader: ClassLoader,
    sdkWhitelist: List<SdkWhitelistEntry>,
    dependencyClassLoaders: List<PluginClassLoader>,
) : PluginClassLoader(emptyArray(), hostClassLoader, sdkWhitelist, dependencyClassLoaders) {

    /** [content] resolved once, via [resolveJarEntries], into its flat entry-name-to-bytes map. */
    private val entries: Map<String, ByteArray> by lazy { resolveJarEntries(content) }

    /**
     * Defines [name] directly from its pinned `.class` entry bytes via [defineClass], instead of
     * resolving it through [java.net.URLClassLoader]'s own URL-based lookup.
     */
    override fun findClass(name: String): Class<*> {
        val entryName = name.replace('.', '/') + ".class"
        val bytes = entries[entryName] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    /**
     * Resolves [name] to an in-memory [URL] over its pinned bytes, or `null` if no such entry was
     * pinned.
     */
    override fun findResource(name: String): URL? {
        val bytes = entries[name] ?: return null
        return URL("pluggiat-pinned", null, -1, name, PinnedResourceUrlStreamHandler(bytes))
    }

    /**
     * Same as [findResource], but as the single-or-empty [java.util.Enumeration] expected by
     * [java.lang.ClassLoader.getResources] - a pinned candidate never has more than one entry per
     * name, since [resolveJarEntries] already resolves duplicates to a single winner.
     */
    override fun findResources(name: String): java.util.Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))

    /**
     * Serves the fixed [bytes] of a single pinned resource as the content of an otherwise
     * meaningless `pluggiat-pinned:` URL, so [findResource] can return a real [URL] without ever
     * writing the resource back to disk.
     */
    private class PinnedResourceUrlStreamHandler(private val bytes: ByteArray) : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection = object : URLConnection(u) {
            override fun connect() {
                // Nothing to connect to - the bytes are already in memory.
            }

            override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        }
    }
}
