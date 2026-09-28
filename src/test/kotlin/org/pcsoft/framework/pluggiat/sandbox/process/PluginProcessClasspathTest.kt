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

package org.pcsoft.framework.pluggiat.sandbox.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies [PluginProcessClasspath]'s two resolution paths: a plain on-disk plugin location, and a
 * plugin's already-pinned bytes materialized into a subprocess working directory.
 */
class PluginProcessClasspathTest {

    /**
     * Use case: a single JAR-file location is returned unchanged as the one-element classpath.
     */
    @Test
    fun `a single jar file location resolves to itself`() {
        val jar = Files.createTempFile("plugin", ".jar")

        val result = PluginProcessClasspath.jarsFor(jar)

        assertEquals(listOf(jar), result)
    }

    /**
     * Use case: a folder location resolves to every `*.jar` file directly inside it.
     */
    @Test
    fun `a folder location resolves to every jar file inside it`() {
        val folder = Files.createTempDirectory("plugin-folder")
        val first = Files.createFile(folder.resolve("first.jar"))
        val second = Files.createFile(folder.resolve("second.jar"))
        Files.createFile(folder.resolve("readme.txt"))

        val result = PluginProcessClasspath.jarsFor(folder)

        assertEquals(setOf(first, second), result.toSet())
    }

    /**
     * Use case: a mounted-ZIP location (path ending in `.zip`) is rejected outright - a subprocess needs
     * a real file-system classpath, which a mounted ZIP does not provide.
     */
    @Test
    fun `a mounted zip location is rejected`() {
        val zip = Path.of("plugin-location.zip")

        val exception = assertThrows(IllegalStateException::class.java) { PluginProcessClasspath.jarsFor(zip) }

        assertTrue(exception.message!!.contains("mounted ZIP_JAR"))
    }

    /**
     * Use case: single pinned content is materialized as one JAR file, under the fixed
     * `pluggiat-pinned-plugin.jar` name, inside the given target directory.
     */
    @Test
    fun `single pinned content is materialized as one jar file`() {
        val targetDirectory = Files.createTempDirectory("pinned-single")
        val content = PinnedPluginContent.Single(bytes = byteArrayOf(1, 2, 3))

        val result = PluginProcessClasspath.jarsFor(content, Path.of("original-location.jar"), targetDirectory)

        assertEquals(1, result.size)
        assertEquals("pluggiat-pinned-plugin.jar", result.single().fileName.toString())
        assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(result.single()).toList())
    }

    /**
     * Use case: multi-file pinned content is materialized as one file per entry, in deterministic
     * (name-sorted) order, each under its own (bare) file name.
     */
    @Test
    fun `multi file pinned content is materialized in sorted order`() {
        val targetDirectory = Files.createTempDirectory("pinned-multi")
        val content = PinnedPluginContent.Multi(
            filesByName = mapOf(
                "b.jar" to byteArrayOf(2),
                "a.jar" to byteArrayOf(1),
            ),
        )

        val result = PluginProcessClasspath.jarsFor(content, Path.of("original-location"), targetDirectory)

        assertEquals(listOf("a.jar", "b.jar"), result.map { it.fileName.toString() })
        assertEquals(listOf<Byte>(1), Files.readAllBytes(result[0]).toList())
        assertEquals(listOf<Byte>(2), Files.readAllBytes(result[1]).toList())
    }

    /**
     * Use case: a directory-traversal file name (`../../evil.jar`) in pinned content is reduced to its
     * bare file name before being written, so the materialized file always stays inside the given target
     * directory.
     */
    @Test
    fun `a pinned file name with directory traversal is reduced to its bare name`() {
        val targetDirectory = Files.createTempDirectory("pinned-traversal")
        val content = PinnedPluginContent.Multi(filesByName = mapOf("../../evil.jar" to byteArrayOf(9)))

        val result = PluginProcessClasspath.jarsFor(content, Path.of("original-location"), targetDirectory)

        assertEquals(targetDirectory.resolve("evil.jar"), result.single())
    }

    /**
     * Use case: pinned content for a plugin whose original location is a mounted ZIP is rejected just
     * like the plain path-based lookup - pinning does not make a mounted ZIP location startable.
     */
    @Test
    fun `pinned content for a mounted zip location is rejected`() {
        val targetDirectory = Files.createTempDirectory("pinned-zip-rejected")
        val content = PinnedPluginContent.Single(bytes = byteArrayOf(1))

        assertThrows(IllegalStateException::class.java) {
            PluginProcessClasspath.jarsFor(content, Path.of("plugin-location.zip"), targetDirectory)
        }
    }
}
