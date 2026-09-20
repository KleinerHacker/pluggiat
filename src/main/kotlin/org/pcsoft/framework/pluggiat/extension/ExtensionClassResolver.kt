package org.pcsoft.framework.pluggiat.extension

/**
 * Resolves the fully qualified class name of a plugin extension implementation, as declared under
 * `extensions.<key>[].implementation`, to a loadable [Class].
 */
interface ExtensionClassResolver {
    /**
     * Resolves [fqcn] to its [Class].
     *
     * @throws ClassNotFoundException if no class with this name can be loaded
     */
    fun resolve(fqcn: String): Class<*>
}

/**
 * Default [ExtensionClassResolver], resolving classes via the calling class loader.
 *
 * Not isolated to a specific plugin's class loader; a dedicated, classloader-isolated
 * implementation is provided as part of the plugin class loading mechanism.
 */
internal object DefaultExtensionClassResolver : ExtensionClassResolver {
    override fun resolve(fqcn: String): Class<*> = Class.forName(fqcn)
}
