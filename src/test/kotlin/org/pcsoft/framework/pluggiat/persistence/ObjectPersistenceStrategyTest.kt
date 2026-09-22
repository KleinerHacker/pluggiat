package org.pcsoft.framework.pluggiat.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObjectPersistenceStrategyTest {

    /**
     * Use case: `read` returns exactly what the host-provided getter function returns for the given
     * `(pluginId, key)` pair.
     */
    @Test
    fun `read delegates to the getter function`() {
        val strategy = ObjectPersistenceStrategy(
            getter = { pluginId, key -> "$pluginId:$key" },
            setter = { _, _, _ -> },
        )

        assertEquals("pluginA:checksum", strategy.read("pluginA", "checksum"))
    }

    /**
     * Use case: `read` passes `null` through unchanged when the host-provided getter has no value
     * for the given `(pluginId, key)` pair.
     */
    @Test
    fun `read returns null when the getter returns null`() {
        val strategy = ObjectPersistenceStrategy(
            getter = { _, _ -> null },
            setter = { _, _, _ -> },
        )

        assertNull(strategy.read("pluginA", "checksum"))
    }

    /**
     * Use case: `write` invokes the host-provided setter function exactly once with the full
     * `(pluginId, key, value)` triple.
     */
    @Test
    fun `write delegates to the setter function`() {
        var recorded: Triple<String, String, String>? = null
        val strategy = ObjectPersistenceStrategy(
            getter = { _, _ -> null },
            setter = { pluginId, key, value -> recorded = Triple(pluginId, key, value) },
        )

        strategy.write("pluginA", "checksum", "abc123")

        assertEquals(Triple("pluginA", "checksum", "abc123"), recorded)
    }

    /**
     * Use case: a value written through the setter is readable through the getter when both
     * functions delegate to the same backing store, as a host would typically wire them.
     */
    @Test
    fun `writes and reads back a value through a shared backing store`() {
        val store = mutableMapOf<Pair<String, String>, String>()
        val strategy = ObjectPersistenceStrategy(
            getter = { pluginId, key -> store[pluginId to key] },
            setter = { pluginId, key, value -> store[pluginId to key] = value },
        )

        strategy.write("pluginA", "checksum", "abc123")

        assertEquals("abc123", strategy.read("pluginA", "checksum"))
    }
}
