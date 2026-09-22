package org.pcsoft.framework.pluggiat.persistence

/**
 * Host-provided lookup for [CustomPersistenceStrategy.read].
 *
 * Must be a cheap, synchronous call.
 */
fun interface PersistenceReadCallback {
    fun read(pluginId: String, key: String): String?
}

/**
 * Host-provided storage for [CustomPersistenceStrategy.write].
 *
 * Must be a cheap, synchronous call.
 */
fun interface PersistenceWriteCallback {
    fun write(pluginId: String, key: String, value: String)
}

/**
 * A [PluginPersistenceStrategy] delegating to host-provided function interfaces, for hosts that
 * already have their own storage mechanism.
 *
 * @property readCallback host-provided lookup invoked from [read]
 * @property writeCallback host-provided storage invoked from [write]
 */
class CustomPersistenceStrategy(
    private val readCallback: PersistenceReadCallback,
    private val writeCallback: PersistenceWriteCallback,
) : PluginPersistenceStrategy {
    override fun read(pluginId: String, key: String): String? = readCallback.read(pluginId, key)

    override fun write(pluginId: String, key: String, value: String) {
        writeCallback.write(pluginId, key, value)
    }
}
