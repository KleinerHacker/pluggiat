package org.pcsoft.framework.pluggiat.persistence

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NoPersistenceStrategyTest {

    /**
     * Use case: reads always resolve to `null`, regardless of prior writes - the strategy keeps no
     * state at all.
     */
    @Test
    fun `read always returns null even after a write`() {
        val strategy = NoPersistenceStrategy()
        strategy.write("plugin-a", "checksum", "abc")

        assertNull(strategy.read("plugin-a", "checksum"))
    }
}
