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

import java.sql.Connection
import javax.sql.DataSource

/**
 * A [PluginPersistenceStrategy] backed by a JDBC [dataSource], using plain JDBC only (no ORM/new
 * dependency). Creates its own table `plugin_state(plugin_id, plugin_key, plugin_value)` on first
 * use if it does not exist yet. Column names avoid the reserved SQL words `key`/`value` so the
 * table works unquoted across common JDBC dialects.
 *
 * @property dataSource JDBC data source backing this strategy; the `plugin_state` table is created
 * on it (if missing) as soon as this strategy is instantiated
 */
class DatabasePersistenceStrategy(private val dataSource: DataSource) : PluginPersistenceStrategy {

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS plugin_state (
                        plugin_id VARCHAR(255) NOT NULL,
                        plugin_key VARCHAR(255) NOT NULL,
                        plugin_value VARCHAR(4000) NOT NULL,
                        PRIMARY KEY (plugin_id, plugin_key)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override fun read(pluginId: String, key: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT plugin_value FROM plugin_state WHERE plugin_id = ? AND plugin_key = ?").use { statement ->
                statement.setString(1, pluginId)
                statement.setString(2, key)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString("plugin_value") else null
                }
            }
        }

    override fun write(pluginId: String, key: String, value: String) {
        dataSource.connection.use { connection -> upsert(connection, pluginId, key, value) }
    }

    private fun upsert(connection: Connection, pluginId: String, key: String, value: String) {
        val updated = connection.prepareStatement("UPDATE plugin_state SET plugin_value = ? WHERE plugin_id = ? AND plugin_key = ?").use { statement ->
            statement.setString(1, value)
            statement.setString(2, pluginId)
            statement.setString(3, key)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement("INSERT INTO plugin_state (plugin_id, plugin_key, plugin_value) VALUES (?, ?, ?)").use { statement ->
                statement.setString(1, pluginId)
                statement.setString(2, key)
                statement.setString(3, value)
                statement.executeUpdate()
            }
        }
    }
}
