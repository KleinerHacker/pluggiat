package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.SdkWhitelistEntryBuilder
import org.pcsoft.framework.pluggiat.classloader.DisallowPluginDependencyStrategy
import org.pcsoft.framework.pluggiat.classloader.SdkWhitelistEntry
import org.pcsoft.framework.pluggiat.extension.ExporterTestConfig
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import java.nio.file.Path

/**
 * Verifies the `pluginManager { ... }` builder DSL's nested blocks not already exercised by other
 * tests: `sdkWhitelistEntry`, `extensionPoint`, a location's `securityOverride`/
 * `dependencyStrategyOverride`, and [SdkWhitelistEntryBuilder] in isolation.
 */
class PluginManagerBuilderTest {

    /**
     * Use case: [SdkWhitelistEntryBuilder] builds an [SdkWhitelistEntry] with its `packageName` and
     * `recursive` fields.
     */
    @Test
    fun `SdkWhitelistEntryBuilder builds the configured entry`() {
        val builder = SdkWhitelistEntryBuilder()
        builder.packageName = "com.example.sdk"
        builder.recursive = false

        val entry = builder.build()

        assertEquals("com.example.sdk", entry.packageName)
        assertEquals(false, entry.recursive)
    }

    /**
     * Use case: `sdkWhitelistEntry { ... }` and `extensionPoint(...)` on the builder DSL add their
     * respective entries to the resulting [org.pcsoft.framework.pluggiat.PluginManagerConfiguration].
     */
    @Test
    fun `sdkWhitelistEntry and extensionPoint populate the configuration`() {
        val manager = pluginManager {
            sdkWhitelistEntry {
                packageName = "com.example.sdk"
                recursive = false
            }
            extensionPoint(ExporterTestConfig::class)
        }

        assertEquals(listOf(SdkWhitelistEntry("com.example.sdk", recursive = false)), manager.config.sdkWhitelist)
        assertEquals(listOf(ExporterTestConfig::class), manager.config.extensionPointClasses)
    }

    /**
     * Use case: a location's own `securityOverride { addStrategy(...) }` block and its
     * `dependencyStrategyOverride` property are both reflected on the built [org.pcsoft.framework.pluggiat.scanner.PluginLocation].
     */
    @Test
    fun `location securityOverride and dependencyStrategyOverride are applied`() {
        val ownStrategy: PluginSecurityStrategy = InsecureSecurityStrategy()
        val dependencyStrategy = DisallowPluginDependencyStrategy()

        val manager = pluginManager {
            location {
                path = Path.of(".")
                type = PluginLocationType.EXTERNAL
                scanStrategy = SingleJarScanStrategy()
                dependencyStrategyOverride = dependencyStrategy
                securityOverride {
                    addStrategy(ownStrategy)
                }
            }
        }

        val location = manager.config.pluginLocations.single()
        assertEquals(listOf(ownStrategy), location.securityOverride)
        assertSame(dependencyStrategy, location.dependencyStrategyOverride)
        assertTrue(location.scanStrategy is SingleJarScanStrategy)
    }
}
