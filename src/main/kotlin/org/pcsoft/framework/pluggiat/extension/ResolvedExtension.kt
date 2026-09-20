package org.pcsoft.framework.pluggiat.extension

/**
 * A single extension entry that has been resolved and instantiated for one plugin.
 *
 * @property key the extension point key this entry was contributed to
 * @property pluginId id of the plugin that contributed this entry
 * @property configuration the fully mapped, host-defined configuration object
 * @property instance the instantiated plugin implementation, implementing the extension point's host plugin API interface
 */
data class ResolvedExtension(
    val key: String,
    val pluginId: String,
    val configuration: ExtensionConfiguration<*>,
    val instance: Any,
)
