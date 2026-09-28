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

package org.pcsoft.framework.pluggiat.scanner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.pcsoft.framework.pluggiat.classloader.jar.resolveJarEntries
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import org.pcsoft.framework.pluggiat.security.PluginSecurity
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Verifies the bounds of [PluginResourceLimits] where they are actually enforced: while a candidate's
 * own bytes are read ([PinnedPluginContentReader]) and while its archives are unpacked
 * ([resolveJarEntries]).
 *
 * All of this happens *before* any security strategy has accepted the candidate, so an unbounded read
 * here would be a denial of service that needs neither a signature nor a successful load.
 */
class PluginResourceLimitsTest {

    /**
     * A file of [size] bytes without writing that many: `setLength` sets the file's length and lets the
     * file system fill it lazily, which keeps this test fast even for a quarter of a gigabyte.
     */
    private fun sparseFileOf(path: Path, size: Long): Path {
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(size) }
        return path
    }

    /** A JAR containing [nested] as its single `.jar` entry - one more level of archive nesting. */
    private fun jarContainingJar(nested: ByteArray, entryName: String): ByteArray {
        val out = ByteArrayOutputStream()
        JarOutputStream(out).use { jar ->
            jar.putNextEntry(JarEntry(entryName))
            jar.write(nested)
            jar.closeEntry()
        }
        return out.toByteArray()
    }

    /** A plain JAR with one harmless text entry - the innermost payload of a nesting chain. */
    private fun leafJar(): ByteArray {
        val out = ByteArrayOutputStream()
        JarOutputStream(out).use { jar ->
            jar.putNextEntry(JarEntry("payload.txt"))
            jar.write("payload".toByteArray())
            jar.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Use case: a candidate file larger than [PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES] is
     * rejected with [PluginContentLimitExceededException] by its size alone - it is never read into
     * memory, which is exactly what an oversized candidate is aiming for.
     */
    @Test
    fun `rejects a candidate file above the size limit`(@TempDir tempDir: Path) {
        val oversized = sparseFileOf(tempDir.resolve("oversized.jar"), PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES + 1)

        val exception = assertThrows(PluginContentLimitExceededException::class.java) {
            PinnedPluginContentReader.read(oversized)
        }

        assertTrue(exception.message!!.contains("maximum candidate file size"))
    }

    /**
     * Use case: a folder candidate whose files are individually acceptable but together exceed
     * [PluginResourceLimits.MAX_CANDIDATE_TOTAL_SIZE_BYTES] is rejected as well - the per-file bound
     * alone could otherwise be circumvented by shipping many merely large JARs.
     */
    @Test
    fun `rejects a folder candidate above the total size limit`(@TempDir tempDir: Path) {
        val folder = Files.createDirectory(tempDir.resolve("plugin-a"))
        // Each file is exactly at the per-file bound, so only their sum can trip the total bound.
        val perFileMaximum = PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES
        val fileCount = (PluginResourceLimits.MAX_CANDIDATE_TOTAL_SIZE_BYTES / perFileMaximum + 1).toInt()
        repeat(fileCount) { index -> sparseFileOf(folder.resolve("jar-$index.jar"), perFileMaximum) }

        val exception = assertThrows(PluginContentLimitExceededException::class.java) {
            PinnedPluginContentReader.read(folder)
        }

        assertTrue(exception.message!!.contains("maximum total size"))
    }

    /**
     * Use case: a candidate nesting archives deeper than [PluginResourceLimits.MAX_NESTING_DEPTH] is
     * rejected instead of being unpacked recursively until the stack or the heap gives out.
     */
    @Test
    fun `rejects a candidate nesting archives beyond the depth limit`() {
        var bytes = leafJar()
        repeat(PluginResourceLimits.MAX_NESTING_DEPTH + 1) { level -> bytes = jarContainingJar(bytes, "level-$level.jar") }

        val exception = assertThrows(PluginContentLimitExceededException::class.java) {
            resolveJarEntries(PinnedPluginContent.Single(bytes))
        }

        assertTrue(exception.message!!.contains("nests archives deeper"))
    }

    /**
     * Use case: nesting *at* the allowed depth still resolves normally - the bound rejects abuse without
     * breaking the legitimate `ZIP_JAR` packaging style, which is itself one level of nesting.
     */
    @Test
    fun `resolves a candidate nested within the depth limit`() {
        var bytes = leafJar()
        repeat(PluginResourceLimits.MAX_NESTING_DEPTH) { level -> bytes = jarContainingJar(bytes, "level-$level.jar") }

        val entries = resolveJarEntries(PinnedPluginContent.Single(bytes))

        assertEquals(setOf("payload.txt"), entries.keys)
    }

    /**
     * Use case: the bytes a candidate is loaded from are the bytes that were checked, even when the file
     * on disk is replaced in between - the check-to-load window a swap would need is closed by pinning
     * (see [PinnedPluginContent]).
     */
    @Test
    fun `a file swapped after the security check does not change what is loaded`(@TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        PluginScannerTestFixtures.writeJar(
            jarPath,
            mapOf(
                "META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a"),
                "marker.txt" to "original content",
            ),
        )
        val location = PluginLocation(
            path = tempDir,
            type = PluginLocationType.EXTERNAL,
            scanStrategy = SingleJarScanStrategy(),
            securityOverride = listOf(InsecureSecurityStrategy()),
        )
        val scanned = PluginScanner(security = PluginSecurity()).scan(listOf(location)).single()
        assertEquals(PluginScanStatus.LOADED, scanned.status)

        // The candidate is replaced on disk *after* it passed the check - the classic TOCTOU attempt.
        PluginScannerTestFixtures.writeJar(
            jarPath,
            mapOf(
                "META-INF/plugin.yml" to PluginScannerTestFixtures.validManifestYaml("plugin-a"),
                "marker.txt" to "swapped content",
            ),
        )

        val entries = resolveJarEntries(requireNotNull(scanned.pinnedContent))
        assertEquals("original content", entries.getValue("marker.txt").toString(Charsets.UTF_8))
    }
}
