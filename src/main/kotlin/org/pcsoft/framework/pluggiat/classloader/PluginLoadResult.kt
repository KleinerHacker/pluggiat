package org.pcsoft.framework.pluggiat.classloader

/**
 * Outcome of [PluginLoader.load].
 */
sealed interface PluginLoadResult {
    /** The plugin was loaded successfully. */
    data class Loaded(val plugin: LoadedPlugin) : PluginLoadResult

    /**
     * The plugin could not be loaded, e.g. due to a missing `required` dependency.
     *
     * @property pluginId id of the invalid plugin, `null` if it could not be determined
     * @property reason human-readable reason
     */
    data class Invalid(val pluginId: String?, val reason: String) : PluginLoadResult
}
