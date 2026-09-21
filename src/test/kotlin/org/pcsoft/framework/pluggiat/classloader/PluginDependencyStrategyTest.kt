package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Developer tests for the built-in [PluginDependencyStrategy] implementations.
 */
class PluginDependencyStrategyTest {

    private val locationA = Path.of("/a")
    private val locationB = Path.of("/b")
    private val locationC = Path.of("/c")

    /**
     * [DisallowPluginDependencyStrategy] must reject visibility even between plugins of the same
     * location, since it is the only way to suppress same-location dependencies entirely.
     */
    @Test
    fun `disallow strategy rejects even same-location visibility`() {
        val strategy = DisallowPluginDependencyStrategy()

        assertFalse(strategy.isVisible(locationA, locationA))
        assertFalse(strategy.isVisible(locationA, locationB))
    }

    /**
     * [LocationPluginDependencyStrategy] must always allow same-location visibility, allow
     * visibility onto explicitly configured locations, and reject everything else.
     */
    @Test
    fun `location strategy allows own and configured locations only`() {
        val strategy = LocationPluginDependencyStrategy(allowedLocations = setOf(locationB))

        assertTrue(strategy.isVisible(locationA, locationA))
        assertTrue(strategy.isVisible(locationA, locationB))
        assertFalse(strategy.isVisible(locationA, locationC))
    }

    /**
     * [UnrestrictedPluginDependencyStrategy], the framework default, must allow visibility between
     * any two locations.
     */
    @Test
    fun `unrestricted strategy allows any location`() {
        val strategy = UnrestrictedPluginDependencyStrategy()

        assertTrue(strategy.isVisible(locationA, locationA))
        assertTrue(strategy.isVisible(locationA, locationB))
        assertTrue(strategy.isVisible(locationA, locationC))
    }
}
