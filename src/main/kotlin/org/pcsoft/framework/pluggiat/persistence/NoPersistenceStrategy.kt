package org.pcsoft.framework.pluggiat.persistence

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [PluginPersistenceStrategy]: reads always resolve to `null`, writes are a no-op.
 *
 * Not recommended for production software - any framework state that relies on persistence (e.g.
 * the checksum security strategy, or the plugin enabled/disabled status) effectively resets on
 * every host application start when this strategy is used.
 */
class NoPersistenceStrategy : PluginPersistenceStrategy {
    private val logger = LoggerFactory.getLogger(NoPersistenceStrategy::class.java)
    private val warned = AtomicBoolean(false)

    override fun read(pluginId: String, key: String): String? {
        warnOnce()
        return null
    }

    override fun write(pluginId: String, key: String, value: String) {
        warnOnce()
    }

    private fun warnOnce() {
        if (warned.compareAndSet(false, true)) {
            logger.warn("NoPersistenceStrategy is in use - not recommended for productive software, no plugin state survives a restart")
        }
    }
}
