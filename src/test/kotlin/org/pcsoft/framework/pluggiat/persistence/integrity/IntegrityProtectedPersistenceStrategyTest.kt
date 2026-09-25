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

package org.pcsoft.framework.pluggiat.persistence.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.persistence.FilePersistenceStrategy
import java.nio.file.Files
import java.nio.file.Path

class IntegrityProtectedPersistenceStrategyTest {

    /**
     * Use case: a value written through the decorator is read back unchanged, because its stored
     * HMAC still matches the stored value.
     */
    @Test
    fun `reads back a written value when its HMAC still matches`(@TempDir tempDir: Path) {
        val strategy = newStrategy(tempDir)

        strategy.write("plugin-a", "checksum", "abc123")

        assertEquals("abc123", strategy.read("plugin-a", "checksum"))
    }

    /**
     * Use case: a value tampered with directly in the underlying storage - without adjusting its
     * HMAC - is no longer returned; the manipulation is detected and the value treated as unset.
     */
    @Test
    fun `detects a value tampered with directly in the underlying storage`(@TempDir tempDir: Path) {
        val delegate = FilePersistenceStrategy(tempDir.resolve("state.properties"))
        val strategy = IntegrityProtectedPersistenceStrategy(delegate, tempDir.resolve("state.properties.key"))
        strategy.write("plugin-a", "enabled", "false")

        delegate.write("plugin-a", "enabled", "true")

        assertNull(strategy.read("plugin-a", "enabled"))
    }

    /**
     * Use case: on first access, no key file exists yet - one is generated so that a subsequently
     * written value can be verified again.
     */
    @Test
    fun `generates a key file on first start`(@TempDir tempDir: Path) {
        val keyPath = tempDir.resolve("state.properties.key")
        val strategy = newStrategy(tempDir, keyPath)
        assertTrue(Files.notExists(keyPath))

        strategy.write("plugin-a", "checksum", "abc123")

        assertTrue(Files.exists(keyPath))
        assertEquals("abc123", strategy.read("plugin-a", "checksum"))
    }

    /**
     * Use case: after a restart, a freshly constructed instance reuses the existing key file
     * instead of generating a new one, so previously written values keep verifying successfully.
     */
    @Test
    fun `reuses the existing key file after a restart`(@TempDir tempDir: Path) {
        val keyPath = tempDir.resolve("state.properties.key")
        newStrategy(tempDir, keyPath).write("plugin-a", "checksum", "abc123")
        val keyAfterFirstStart = Files.readString(keyPath)

        val restarted = newStrategy(tempDir, keyPath)
        val value = restarted.read("plugin-a", "checksum")

        assertEquals("abc123", value)
        assertEquals(keyAfterFirstStart, Files.readString(keyPath))
    }

    /**
     * Use case: two independently generated key files never collide, confirming key generation is
     * actually randomized rather than a fixed value.
     */
    @Test
    fun `generates a different key for each independent storage location`(@TempDir tempDir: Path) {
        val keyPathA = tempDir.resolve("a.key")
        val keyPathB = tempDir.resolve("b.key")
        newStrategy(tempDir, keyPathA, "a.properties").write("plugin-a", "checksum", "abc123")
        newStrategy(tempDir, keyPathB, "b.properties").write("plugin-a", "checksum", "abc123")

        assertNotEquals(Files.readString(keyPathA), Files.readString(keyPathB))
    }

    private fun newStrategy(
        tempDir: Path,
        keyPath: Path = tempDir.resolve("state.properties.key"),
        fileName: String = "state.properties",
    ): IntegrityProtectedPersistenceStrategy =
        IntegrityProtectedPersistenceStrategy(FilePersistenceStrategy(tempDir.resolve(fileName)), keyPath)
}
