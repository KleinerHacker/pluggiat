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

package org.pcsoft.framework.pluggiat.classloader.jar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Developer tests for [resolveJarEntries]: the single shared entry-resolution function used both by
 * signature verification and by [org.pcsoft.framework.pluggiat.classloader.PinnedPluginClassLoader]
 * to load classes, so both sides must agree on which entry among duplicate ZIP entry names wins.
 */
class JarEntryResolverTest {

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipStream ->
            for ((name, content) in entries) {
                zipStream.putNextEntry(ZipEntry(name))
                zipStream.write(content.toByteArray(Charsets.UTF_8))
                zipStream.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Builds raw, STORED-method ZIP local file header entries back to back (no central directory),
     * which [java.util.zip.ZipInputStream] reads just fine sequentially. Used only to construct a
     * ZIP with a duplicate entry name for [`duplicate entry names resolve to the last occurrence`] -
     * [java.util.zip.ZipOutputStream] itself refuses to write a duplicate entry name.
     */
    private fun zipBytesAllowingDuplicates(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content) in entries) {
            out.write(localFileHeader(name, content.toByteArray(Charsets.UTF_8)))
        }
        return out.toByteArray()
    }

    private fun localFileHeader(name: String, content: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(content) }.value
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        fun writeLE(value: Long, byteCount: Int) {
            for (i in 0 until byteCount) out.write(((value shr (8 * i)) and 0xFF).toInt())
        }
        writeLE(0x04034b50, 4) // local file header signature
        writeLE(20, 2) // version needed to extract
        writeLE(0, 2) // general purpose bit flag
        writeLE(0, 2) // compression method: STORED
        writeLE(0, 2) // last mod file time
        writeLE(0, 2) // last mod file date
        writeLE(crc, 4)
        writeLE(content.size.toLong(), 4) // compressed size
        writeLE(content.size.toLong(), 4) // uncompressed size
        writeLE(nameBytes.size.toLong(), 2)
        writeLE(0, 2) // extra field length
        out.write(nameBytes)
        out.write(content)
        return out.toByteArray()
    }

    /**
     * Use case: a ZIP with two entries of the same name resolves to the content of the *last*
     * occurrence, matching the JDK's own `java.util.zip.ZipFile` behavior for duplicate entry names -
     * so verification (which must see the same content) and loading can never diverge on which of
     * the two "wins".
     */
    @Test
    fun `duplicate entry names resolve to the last occurrence`() {
        val bytes = zipBytesAllowingDuplicates("some/Class.class" to "first", "some/Class.class" to "second")

        val entries = resolveJarEntries(PinnedPluginContent.Single(bytes))

        assertEquals(1, entries.size)
        assertArrayEquals("second".toByteArray(Charsets.UTF_8), entries.getValue("some/Class.class"))
    }

    /**
     * Use case: an entry whose name ends in `.jar` (as found in the outer ZIP of a `ZIP_JAR`
     * candidate, which itself just contains further JARs) is transparently unpacked and merged in,
     * so the class entries inside it are resolved directly.
     */
    @Test
    fun `entries ending in jar are unpacked and merged`() {
        val innerJarBytes = zipBytes("some/Class.class" to "class-bytes")
        val outerZipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zipStream ->
                zipStream.putNextEntry(ZipEntry("plugin.jar"))
                zipStream.write(innerJarBytes)
                zipStream.closeEntry()
            }
        }.toByteArray()

        val entries = resolveJarEntries(PinnedPluginContent.Single(outerZipBytes))

        assertEquals(setOf("some/Class.class"), entries.keys)
        assertArrayEquals("class-bytes".toByteArray(Charsets.UTF_8), entries.getValue("some/Class.class"))
    }

    /**
     * Use case: [PinnedPluginContent.Multi] is resolved file by file in sorted file name order and
     * merged, so a later (alphabetically) file's entry wins over an earlier file's entry of the same
     * name - deterministic across the whole candidate, not just within one file.
     */
    @Test
    fun `multi content merges files in sorted file name order`() {
        val fileA = zipBytes("shared.txt" to "from-a")
        val fileB = zipBytes("shared.txt" to "from-b")
        val content = PinnedPluginContent.Multi(mapOf("b.jar" to fileB, "a.jar" to fileA))

        val entries = resolveJarEntries(content)

        assertArrayEquals("from-b".toByteArray(Charsets.UTF_8), entries.getValue("shared.txt"))
    }
}
