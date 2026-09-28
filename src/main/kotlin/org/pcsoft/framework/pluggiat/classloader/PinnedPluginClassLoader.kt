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
 * Inherits [PluginClassLoader]'s `loadClass` precedence (platform classes, then framework classes
 * from the host, then the SDK whitelist via the host class loader, then this class loader's own
 * [findClass]/[findResource], then dependency class loaders) unchanged - only where
 * classes/resources ultimately come from differs. Resource lookup additionally refuses to serve
 * anything below [PluginClassLoader.FRAMEWORK_RESOURCE_PREFIX]: a plugin must not be able to
 * shadow one of pluggiat's own resources (a `META-INF/services` provider file, the manifest schema,
 * ...) from its own pinned bytes, which would be a way to influence framework behaviour without
 * ever loading a framework class.
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
        // SECURITY: resolved out of the pinned entry map, never re-read from disk - these are the exact bytes
        // SECURITY: the security chain verified, so check-time and load-time content cannot differ.
        val bytes = entries[entryName] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    /**
     * Resolves [name] to an in-memory [URL] over its pinned bytes, or `null` if no such entry was
     * pinned or if [name] is a framework resource the plugin must not shadow.
     */
    override fun findResource(name: String): URL? {
        // SECURITY: a plugin must not be able to serve one of pluggiat's own resources (a services file, the
        // SECURITY: manifest schema) from its own bytes - that would steer framework behaviour without ever
        // SECURITY: loading a framework class.
        if (isFrameworkResource(name)) return null
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
     * Whether [name] addresses one of pluggiat's own resources, which are served by the host's class
     * loader only and are never taken from a plugin's pinned bytes. Both separator spellings are
     * rejected, since a resource name reaches a class loader unnormalized.
     */
    private fun isFrameworkResource(name: String): Boolean {
        // SECURITY: normalized first: a resource name arrives exactly as the caller wrote it, so "/org/..."
        // SECURITY: and a backslash-separated spelling must be recognized as the same protected prefix.
        val normalized = name.removePrefix("/").replace('\\', '/')
        return normalized.startsWith(FRAMEWORK_RESOURCE_PREFIX)
    }

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
