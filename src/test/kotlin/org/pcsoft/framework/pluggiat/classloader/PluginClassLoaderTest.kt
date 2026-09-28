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

package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import java.nio.file.Files
import java.nio.file.Path

/**
 * Developer tests for [PluginClassLoader]'s class resolution order: platform classes, SDK
 * whitelist, own classpath, declared dependencies.
 */
class PluginClassLoaderTest {

    private val hostClassLoader = javaClass.classLoader

    private fun classLoader(sdkWhitelist: List<SdkWhitelistEntry> = emptyList()): PluginClassLoader =
        PluginClassLoader(emptyArray(), hostClassLoader, sdkWhitelist, emptyList())

    /**
     * Use case: a plugin shipping its own class under the framework's own package prefix does not get that
     * class loaded - the host's copy wins, even though the loader is parent-last and searches the plugin's
     * own JAR before its dependencies. The forged entry is deliberately invalid bytecode, so a loader that
     * did use it would fail with a `ClassFormatError` instead of quietly succeeding.
     */
    @Test
    fun `cannot override a framework class with its own entry`(@TempDir tempDir: Path) {
        val jar = tempDir.resolve("forging-plugin.jar")
        PluginScannerTestFixtures.writeJar(
            jar,
            mapOf(
                "org/pcsoft/framework/pluggiat/sandbox/agent/SandboxGuardRegistry.class" to "not valid bytecode",
                "org/pcsoft/framework/pluggiat/forged-marker.txt" to "forged",
            ),
        )
        val classLoader = PluginClassLoader(arrayOf(jar.toUri().toURL()), hostClassLoader, emptyList(), emptyList())

        try {
            val loaded = classLoader.loadClass("org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry")

            assertEquals(hostClassLoader, loaded.classLoader)
        } finally {
            classLoader.close()
        }
    }

    /**
     * Use case: a pinned plugin candidate cannot serve a resource below the framework's own resource
     * prefix, while its own resources stay reachable - shadowing a framework resource would be a way to
     * influence framework behaviour without ever loading a framework class.
     */
    @Test
    fun `pinned plugin cannot serve a framework resource`(@TempDir tempDir: Path) {
        val jar = tempDir.resolve("forging-resources.jar")
        PluginScannerTestFixtures.writeJar(
            jar,
            mapOf(
                "org/pcsoft/framework/pluggiat/forged-marker.txt" to "forged",
                "plugin-marker.txt" to "own resource",
            ),
        )
        val classLoader = PinnedPluginClassLoader(
            PinnedPluginContent.Single(Files.readAllBytes(jar)),
            hostClassLoader,
            emptyList(),
            emptyList(),
        )

        try {
            assertNull(classLoader.getResource("org/pcsoft/framework/pluggiat/forged-marker.txt"))
            assertNotNull(classLoader.getResource("plugin-marker.txt"))
        } finally {
            classLoader.close()
        }
    }

    /**
     * A plugin class loader without a matching [SdkWhitelistEntry] must not be able to resolve a
     * host-internal class, even though that class is reachable from [hostClassLoader] itself.
     */
    @Test
    fun `cannot load host class outside whitelist`() {
        val classLoader = classLoader(sdkWhitelist = emptyList())

        assertThrows(ClassNotFoundException::class.java) {
            classLoader.loadClass("com.example.hostapp.internal.HostInternalMarker")
        }
    }

    /**
     * A plugin class loader with a matching [SdkWhitelistEntry] must resolve a host class to the
     * very same [Class] instance the host itself uses, so interface casts across the plugin/host
     * boundary work correctly.
     */
    @Test
    fun `can load whitelisted host class as identical Class instance`() {
        val classLoader = classLoader(
            sdkWhitelist = listOf(SdkWhitelistEntry("com.example.hostapp.sdk")),
        )

        val loaded = classLoader.loadClass("com.example.hostapp.sdk.WhitelistedMarker")

        assertEquals(com.example.hostapp.sdk.WhitelistedMarker::class.java, loaded)
    }

    /**
     * JDK platform classes (`java.*`/`javax.*`) must always be resolvable through a plugin class
     * loader, regardless of the configured SDK whitelist, since plugin bytecode unconditionally
     * references core JDK types like `java.lang.Object`.
     */
    @Test
    fun `can load JDK class without whitelist entry`() {
        val classLoader = classLoader(sdkWhitelist = emptyList())

        val loaded = classLoader.loadClass("java.util.ArrayList")

        assertEquals(java.util.ArrayList::class.java, loaded)
    }

    /**
     * A non-recursive [SdkWhitelistEntry] must expose only classes directly inside the configured
     * package, not classes of its sub-packages.
     */
    @Test
    fun `non-recursive whitelist entry exposes only direct package classes`() {
        val classLoader = classLoader(
            sdkWhitelist = listOf(
                SdkWhitelistEntry("com.example.hostapp.sdk", recursive = false),
            ),
        )

        val direct = classLoader.loadClass("com.example.hostapp.sdk.WhitelistedMarker")
        assertEquals(com.example.hostapp.sdk.WhitelistedMarker::class.java, direct)

        assertThrows(ClassNotFoundException::class.java) {
            classLoader.loadClass("com.example.hostapp.sdk.sub.SubPackageMarker")
        }
    }
}
