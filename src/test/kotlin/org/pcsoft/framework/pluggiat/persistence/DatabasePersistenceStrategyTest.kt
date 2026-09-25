/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

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
