package org.pcsoft.framework.pluggiat.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.h2.jdbcx.JdbcDataSource
import javax.sql.DataSource
import java.util.UUID

class DatabasePersistenceStrategyTest {

    private fun inMemoryDataSource(): DataSource =
        JdbcDataSource().apply { setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1") }

    /**
     * Use case: the `plugin_state` table is created automatically on first use, and a written value
     * is read back for the same plugin id and key.
     */
    @Test
    fun `writes and reads back a value, creating the table automatically`() {
        val strategy = DatabasePersistenceStrategy(inMemoryDataSource())

        strategy.write("plugin-a", "checksum", "abc123")

        assertEquals("abc123", strategy.read("plugin-a", "checksum"))
    }

    /**
     * Use case: reading an unknown plugin id/key combination resolves to `null`.
     */
    @Test
    fun `read returns null for an unknown plugin id and key`() {
        val strategy = DatabasePersistenceStrategy(inMemoryDataSource())

        assertNull(strategy.read("plugin-a", "checksum"))
    }

    /**
     * Use case: writing the same plugin id/key twice updates the existing row instead of inserting
     * a duplicate.
     */
    @Test
    fun `overwrites an existing value for the same plugin id and key`() {
        val strategy = DatabasePersistenceStrategy(inMemoryDataSource())

        strategy.write("plugin-a", "checksum", "first")
        strategy.write("plugin-a", "checksum", "second")

        assertEquals("second", strategy.read("plugin-a", "checksum"))
    }
}
