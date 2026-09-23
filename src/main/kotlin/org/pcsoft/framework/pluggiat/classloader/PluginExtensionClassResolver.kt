package org.pcsoft.framework.pluggiat.classloader

import org.pcsoft.framework.pluggiat.extension.ExtensionClassResolver

/**
 * [ExtensionClassResolver] isolated to a single plugin's [PluginClassLoader], replacing
 * `org.pcsoft.framework.pluggiat.extension.DefaultExtensionClassResolver` for actual plugin
 * loading.
 */
class PluginExtensionClassResolver(private val classLoader: PluginClassLoader) : ExtensionClassResolver {
    override fun resolve(fqcn: String): Class<*> = classLoader.loadClass(fqcn)
}
