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
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path

class FilePersistenceStrategyTest {

    /**
     * Use case: a value written under one format is read back correctly, including by a second,
     * freshly constructed strategy instance pointed at the same file (round-trip through disk).
     */
    @ParameterizedTest
    @EnumSource(PersistenceFileFormat::class)
    fun `writes and reads back a value through disk for every file format`(format: PersistenceFileFormat, @TempDir tempDir: Path) {
        val file = tempDir.resolve("state.${format.name.lowercase()}")
        FilePersistenceStrategy(file, format).write("plugin-a", "checksum", "abc123")

        val reloaded = FilePersistenceStrategy(file, format)

        assertEquals("abc123", reloaded.read("plugin-a", "checksum"))
    }

    /**
     * Use case: reading before the backing file exists yet resolves to `null` instead of failing.
     */
    @Test
    fun `read returns null when the backing file does not exist yet`(@TempDir tempDir: Path) {
        val strategy = FilePersistenceStrategy(tempDir.resolve("missing.properties"))

        assertNull(strategy.read("plugin-a", "checksum"))
    }

    /**
     * Use case: entries of different plugin ids and keys stay independent within the same file.
     */
    @Test
    fun `keeps entries of different plugin ids and keys independent`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("state.properties")
        val strategy = FilePersistenceStrategy(file)
        strategy.write("plugin-a", "checksum", "aaa")
        strategy.write("plugin-b", "checksum", "bbb")
        strategy.write("plugin-a", "enabled", "true")

        assertEquals("aaa", strategy.read("plugin-a", "checksum"))
        assertEquals("bbb", strategy.read("plugin-b", "checksum"))
        assertEquals("true", strategy.read("plugin-a", "enabled"))
    }
}
