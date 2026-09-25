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
