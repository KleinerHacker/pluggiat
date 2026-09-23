package org.pcsoft.framework.pluggiat.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CustomPersistenceStrategyTest {

    /**
     * Use case: reads and writes are delegated verbatim to the host-provided callbacks.
     */
    @Test
    fun `delegates reads and writes to the configured callbacks`() {
        val store = mutableMapOf<String, String>()
        val strategy = CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )

        assertNull(strategy.read("plugin-a", "checksum"))
        strategy.write("plugin-a", "checksum", "abc")
        assertEquals("abc", strategy.read("plugin-a", "checksum"))
    }
}
