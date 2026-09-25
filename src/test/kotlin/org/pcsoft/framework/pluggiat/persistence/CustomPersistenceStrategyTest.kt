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
