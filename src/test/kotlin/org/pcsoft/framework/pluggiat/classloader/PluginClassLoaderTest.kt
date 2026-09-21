package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Developer tests for [PluginClassLoader]'s class resolution order: platform classes, SDK
 * whitelist, own classpath, declared dependencies.
 */
class PluginClassLoaderTest {

    private val hostClassLoader = javaClass.classLoader

    private fun classLoader(sdkWhitelist: List<SdkWhitelistEntry> = emptyList()): PluginClassLoader =
        PluginClassLoader(emptyArray(), hostClassLoader, sdkWhitelist, emptyList())

    /**
     * A plugin class loader without a matching [SdkWhitelistEntry] must not be able to resolve a
     * host-internal class, even though that class is reachable from [hostClassLoader] itself.
     */
    @Test
    fun `cannot load host class outside whitelist`() {
        val classLoader = classLoader(sdkWhitelist = emptyList())

        assertThrows(ClassNotFoundException::class.java) {
            classLoader.loadClass("org.pcsoft.framework.pluggiat.classloader.fixtures.internal.HostInternalMarker")
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
            sdkWhitelist = listOf(SdkWhitelistEntry("org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted")),
        )

        val loaded = classLoader.loadClass("org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted.WhitelistedMarker")

        assertEquals(org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted.WhitelistedMarker::class.java, loaded)
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
                SdkWhitelistEntry("org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted", recursive = false),
            ),
        )

        val direct = classLoader.loadClass("org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted.WhitelistedMarker")
        assertEquals(org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted.WhitelistedMarker::class.java, direct)

        assertThrows(ClassNotFoundException::class.java) {
            classLoader.loadClass("org.pcsoft.framework.pluggiat.classloader.fixtures.whitelisted.sub.SubPackageMarker")
        }
    }
}
