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
