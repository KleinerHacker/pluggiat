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
 * Class resolution order:
 * 1. platform classes (`java.*`/`javax.*`, always delegated to the JDK platform class loader
 *    regardless of [sdkWhitelist]),
 * 2. framework classes ([FRAMEWORK_PACKAGE_PREFIX], always delegated to [hostClassLoader] and
 *    never resolvable from the plugin's own JARs - see below),
 * 3. [sdkWhitelist]-matched classes delegated to [hostClassLoader],
 * 4. the plugin's own JARs ([urls]),
 * 5. [dependencyClassLoaders] in declaration order.
 *
 * A plugin can therefore never see host-internal classes outside the whitelist, and never sees
 * another plugin's classes unless that plugin's class loader was explicitly passed in as a
 * dependency.
 *
 * Step 2 is a hard security boundary rather than a convenience: pluggiat's own classes - above all
 * `org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry`, which every guard call
 * injected into instrumented plugin bytecode resolves against - must always be the host's copy. A
 * plugin shipping a class of the same name in its own JAR would otherwise get that copy loaded
 * (parent-last), and its guard calls would land in an attacker-controlled registry that simply
 * allows everything. Framework classes are hence delegated unconditionally, with no fall-back to
 * the plugin's own JARs; a delegation that fails surfaces as [ClassNotFoundException] instead of
 * silently continuing with the plugin's version.
 *
 * @param urls the plugin's own JAR(s) to load classes/resources from
 * @property hostClassLoader the host application's own class loader, consulted for framework and
 * [sdkWhitelist]-matched classes
 * @property sdkWhitelist packages of the host's own SDK exposed to this plugin - cannot widen or
 * override step 1 or step 2
 * @property dependencyClassLoaders class loaders of this plugin's already-loaded dependencies, in
 * declaration order
 */
open class PluginClassLoader(
    urls: Array<URL>,
    private val hostClassLoader: ClassLoader,
    private val sdkWhitelist: List<SdkWhitelistEntry>,
    private val dependencyClassLoaders: List<PluginClassLoader>,
) : URLClassLoader(urls, null) {

    companion object {
        /**
         * Binary name prefix of pluggiat's own classes, which are always loaded from the host and
         * never from a plugin (see this class's own documentation for why).
         */
        internal const val FRAMEWORK_PACKAGE_PREFIX = "org.pcsoft.framework.pluggiat."

        /** [FRAMEWORK_PACKAGE_PREFIX] in the slash-separated form used by resource names. */
        internal const val FRAMEWORK_RESOURCE_PREFIX = "org/pcsoft/framework/pluggiat/"
    }

    private val platformClassLoader: ClassLoader = getPlatformClassLoader()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // SECURITY: one lock per class name, so two threads of the same plugin cannot race this ordered
        // SECURITY: resolution and have one of them define a class the other already resolved elsewhere.
        synchronized(getClassLoadingLock(name)) {
            // SECURITY: an already-defined class is returned as is - a class's identity, and with it which
            // SECURITY: loader (and policy) it belongs to, must never change while the plugin is loaded.
            findLoadedClass(name)?.let { return it.also { if (resolve) resolveClass(it) } }

            // SECURITY: java.*/javax.* always come from the JDK's platform loader, never from the plugin:
            // SECURITY: a plugin-supplied "java.lang.*" class would otherwise become the JDK for that plugin.
            if (isPlatformClass(name)) {
                loadFrom(platformClassLoader, name)?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            // SECURITY: pluggiat's own classes are resolved before the whitelist and before the plugin's own
            // SECURITY: JARs, and only from the host. This is what keeps SandboxGuardRegistry - the class every
            // SECURITY: injected guard call lands in - out of the plugin's reach.
            if (isFrameworkClass(name)) {
                // SECURITY: no fall-back on failure: an unresolvable framework class is an error, never a
                // SECURITY: reason to continue and let the plugin's own copy of that name be used instead.
                val frameworkClass = loadFrom(hostClassLoader, name) ?: throw ClassNotFoundException(name)
                return frameworkClass.also { if (resolve) resolveClass(it) }
            }

            // SECURITY: only explicitly whitelisted host packages are visible; every other host class stays
            // SECURITY: invisible to the plugin (the whole point of a parent-last loader here).
            if (isWhitelisted(name)) {
                loadFrom(hostClassLoader, name)?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            // SECURITY: the plugin's own classes come last of the trusted sources - by now nothing it ships
            // SECURITY: can shadow a platform, framework or whitelisted host class.
            findOwnClass(name)?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }

            // SECURITY: only class loaders of plugins declared as dependencies are consulted, so one plugin
            // SECURITY: never sees another plugin's classes by accident.
            for (dependency in dependencyClassLoaders) {
                loadFrom(dependency, name)?.let { return it.also { clazz -> if (resolve) resolveClass(clazz) } }
            }

            throw ClassNotFoundException(name)
        }
    }

    /**
     * Delegates [name] to [classLoader], mapping only a genuine "not found" to `null`. Every other
     * failure - above all [LinkageError] and its subtypes ([ClassFormatError],
     * [UnsupportedClassVersionError], [NoClassDefFoundError] ...) - is propagated unchanged: a class
     * that exists but cannot be linked must fail the load loudly instead of being silently skipped,
     * which would otherwise let resolution continue with a different, less trusted copy of it.
     */
    private fun loadFrom(classLoader: ClassLoader, name: String): Class<*>? =
        try {
            classLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            // SECURITY: only "absent" is swallowed. Catching Throwable here (as this once did) turned a class
            // SECURITY: that exists but fails to link into "not found", letting resolution fall through to a
            // SECURITY: less trusted source for that same name.
            null
        }

    /** [findClass] with the same "only `ClassNotFoundException` means absent" contract as [loadFrom]. */
    private fun findOwnClass(name: String): Class<*>? =
        try {
            findClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun isPlatformClass(name: String): Boolean =
        name.startsWith("java.") || name.startsWith("javax.")

    // SECURITY: prefix match on the binary name - covers every current and future framework package,
    // SECURITY: which an enumeration of known class names would not.
    private fun isFrameworkClass(name: String): Boolean =
        name.startsWith(FRAMEWORK_PACKAGE_PREFIX)

    private fun isWhitelisted(name: String): Boolean =
        sdkWhitelist.any { entry ->
            if (entry.recursive) {
                // SECURITY: the "." is part of the comparison on purpose: without it, a whitelisted
                // SECURITY: "com.example.sdk" would also expose the unrelated package "com.example.sdkinternal".
                name == entry.packageName || name.startsWith("${entry.packageName}.")
            } else {
                // SECURITY: non-recursive means exactly this package - the class's own package must equal it,
                // SECURITY: so a sub-package cannot ride along on a deliberately narrow whitelist entry.
                name.substringBeforeLast('.', "") == entry.packageName
            }
        }
}
