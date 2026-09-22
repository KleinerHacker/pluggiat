package org.pcsoft.framework.pluggiat.persistence

/**
 * Generic key-value persistence for per-plugin framework and host state (e.g. the accepted
 * checksum of a plugin, or its enabled/disabled status).
 *
 * Exactly one instance is configured for the whole framework, via
 * `org.pcsoft.framework.pluggiat.PluginManagerConfiguration.persistenceStrategy`. Implementations are
 * free to share storage across all plugin ids and keys however they like; from the framework's
 * point of view, each `(pluginId, key)` pair addresses one independent value.
 */
interface PluginPersistenceStrategy {

    /**
     * Reads the value stored for `(pluginId, key)`, or `null` if none is stored.
     */
    fun read(pluginId: String, key: String): String?

    /**
     * Writes [value] for `(pluginId, key)`, replacing any previously stored value.
     */
    fun write(pluginId: String, key: String, value: String)
}
