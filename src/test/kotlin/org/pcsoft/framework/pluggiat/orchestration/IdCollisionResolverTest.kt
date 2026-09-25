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

package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import java.nio.file.Path

/**
 * Verifies [IdCollisionResolver]'s version-only resolution rule for plugin ids colliding across
 * locations.
 */
class IdCollisionResolverTest {

    private fun locationAt(path: Path): PluginLocation = PluginLocation(path, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

    private fun loadedResult(path: Path, id: String, version: String): PluginScanResult = PluginScanResult(
        location = locationAt(path),
        path = path,
        manifest = PluginManifest(id = id, name = id, version = version, minVersion = "1.0.0", icon = "aWNvbg=="),
        status = PluginScanStatus.LOADED,
    )

    /**
     * Use case: two candidates with the same plugin id from different locations, one with a
     * strictly higher version - the higher version is kept as `LOADED`, the other is rejected with
     * `ID_COLLISION`.
     */
    @Test
    fun `higher version wins, the other candidate is rejected`(@TempDir tempDir: Path) {
        val older = loadedResult(tempDir.resolve("a"), "plugin-a", "1.0.0")
        val newer = loadedResult(tempDir.resolve("b"), "plugin-a", "2.0.0")

        val resolved = IdCollisionResolver().resolve(listOf(older, newer))

        val resolvedByPath = resolved.associateBy { it.path }
        assertEquals(PluginScanStatus.ID_COLLISION, resolvedByPath.getValue(older.path).status)
        assertEquals(PluginScanStatus.LOADED, resolvedByPath.getValue(newer.path).status)
    }

    /**
     * Use case: two candidates with the same plugin id and the exact same version - both are
     * rejected with `ID_COLLISION` immediately, without any further tie-breaking.
     */
    @Test
    fun `same version collision rejects all candidates of the group`(@TempDir tempDir: Path) {
        val first = loadedResult(tempDir.resolve("a"), "plugin-a", "1.0.0")
        val second = loadedResult(tempDir.resolve("b"), "plugin-a", "1.0.0")

        val resolved = IdCollisionResolver().resolve(listOf(first, second))

        assertTrue(resolved.all { it.status == PluginScanStatus.ID_COLLISION })
    }

    /**
     * Use case: unrelated plugin ids and candidates without a manifest (e.g. a failed scan) are
     * passed through unchanged.
     */
    @Test
    fun `unrelated candidates and candidates without a manifest are unaffected`(@TempDir tempDir: Path) {
        val single = loadedResult(tempDir.resolve("a"), "plugin-a", "1.0.0")
        val invalid = PluginScanResult(
            location = locationAt(tempDir.resolve("b")),
            path = tempDir.resolve("b"),
            manifest = null,
            status = PluginScanStatus.MANIFEST_INVALID,
            errorMessage = "broken",
        )

        val resolved = IdCollisionResolver().resolve(listOf(single, invalid))

        assertEquals(setOf(single, invalid), resolved.toSet())
    }
}
