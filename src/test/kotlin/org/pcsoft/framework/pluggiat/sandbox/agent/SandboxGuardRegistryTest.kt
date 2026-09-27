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

package org.pcsoft.framework.pluggiat.sandbox.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation

/**
 * Verifies [SandboxGuardRegistry.check] - the runtime call every guard call injected by
 * [GuardAsmVisitorWrapper] resolves against.
 */
class SandboxGuardRegistryTest {

    private class Marker

    /**
     * Use case: [SandboxGuardRegistry.check] for a category listed in the registered policy's
     * `allowedApiCategories` returns normally, without invoking the violation callback.
     */
    @Test
    fun `check allows a category permitted by the registered policy`() {
        val classLoader = Marker::class.java.classLoader
        var violationCalls = 0
        SandboxGuardRegistry.register(
            classLoader, "example",
            PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.NETWORK)),
        ) { _, _ -> violationCalls++ }

        try {
            SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.NETWORK)
            assertEquals(0, violationCalls)
        } finally {
            SandboxGuardRegistry.release(classLoader)
        }
    }

    /**
     * Use case: [SandboxGuardRegistry.check] for a category not permitted by the registered policy
     * invokes the violation callback with a [SandboxViolation] describing the blocked category, then
     * throws [SandboxViolationException] to abort the guarded call.
     */
    @Test
    fun `check reports and blocks a category not permitted by the registered policy`() {
        val classLoader = Marker::class.java.classLoader
        val reported = mutableListOf<SandboxViolation>()
        SandboxGuardRegistry.register(
            classLoader, "example",
            PluginSandboxPolicy(allowedApiCategories = emptySet()),
        ) { pluginId, violation -> reported += violation.also { assertEquals(pluginId, it.pluginId) } }

        try {
            val exception = assertThrows(SandboxViolationException::class.java) {
                SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.FILESYSTEM)
            }
            assertEquals(SandboxApiCategory.FILESYSTEM, exception.violation.category)
            assertEquals(1, reported.size)
            assertEquals(SandboxApiCategory.FILESYSTEM, reported.single().category)
        } finally {
            SandboxGuardRegistry.release(classLoader)
        }
    }

    /**
     * Use case: [SandboxGuardRegistry.check] for a class loader with no registration at all (not a
     * plugin class) is a silent no-op.
     */
    @Test
    fun `check is a no-op for an unregistered class loader`() {
        SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.PROCESS_START)
    }

    /**
     * Use case: after [SandboxGuardRegistry.revoke], [SandboxGuardRegistry.check] blocks *every*
     * category - including the ones the revoked policy used to allow - instead of finding no entry and
     * waving the call through (fail-closed). This is the state a plugin thread that outlived its
     * plugin's unload runs in.
     */
    @Test
    fun `check blocks every category after revoke`() {
        val classLoader = Marker::class.java.classLoader
        val reported = mutableListOf<SandboxViolation>()
        SandboxGuardRegistry.register(
            classLoader, "example",
            PluginSandboxPolicy(allowedApiCategories = SandboxApiCategory.entries.toSet()),
        ) { _, violation -> reported += violation }

        try {
            SandboxGuardRegistry.revoke(classLoader)

            for (category in SandboxApiCategory.entries) {
                val exception = assertThrows(SandboxViolationException::class.java) {
                    SandboxGuardRegistry.check(Marker::class.java, category)
                }
                assertEquals(category, exception.violation.category)
            }
            assertEquals(SandboxApiCategory.entries.size, reported.size)
        } finally {
            SandboxGuardRegistry.release(classLoader)
        }
    }

    /**
     * Use case: a guarded call made from a *different thread* after the plugin was revoked is blocked
     * just the same - the registration is keyed by class loader, not by thread, so a worker thread a
     * plugin left running cannot regain access its plugin no longer has.
     */
    @Test
    fun `check blocks a call from a surviving plugin thread after revoke`() {
        val classLoader = Marker::class.java.classLoader
        SandboxGuardRegistry.register(
            classLoader, "example",
            PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.NETWORK)),
        ) { _, _ -> }

        try {
            SandboxGuardRegistry.revoke(classLoader)
            var thrown: Throwable? = null
            val survivingThread = Thread {
                thrown = runCatching { SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.NETWORK) }.exceptionOrNull()
            }

            survivingThread.start()
            survivingThread.join()

            assertInstanceOf(SandboxViolationException::class.java, thrown)
        } finally {
            SandboxGuardRegistry.release(classLoader)
        }
    }

    /**
     * Use case: re-registering a revoked class loader (what a successful reactivation of its plugin
     * does) lifts the revocation, so an allowed category passes again - the revoked state is not a
     * permanent dead end.
     */
    @Test
    fun `register lifts a previous revocation`() {
        val classLoader = Marker::class.java.classLoader
        val policy = PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.NETWORK))
        SandboxGuardRegistry.register(classLoader, "example", policy) { _, _ -> }
        SandboxGuardRegistry.revoke(classLoader)

        try {
            SandboxGuardRegistry.register(classLoader, "example", policy) { _, _ -> }

            SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.NETWORK)
        } finally {
            SandboxGuardRegistry.release(classLoader)
        }
    }

    /**
     * Use case: [SandboxGuardRegistry.revoke] for a class loader that was never registered leaves it
     * unknown rather than inventing a revoked entry for it - the framework only ever tracks loaders it
     * registered itself.
     */
    @Test
    fun `revoke does not create an entry for an unknown class loader`() {
        val foreignClassLoader = java.net.URLClassLoader(emptyArray())

        SandboxGuardRegistry.revoke(foreignClassLoader)

        SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.FILESYSTEM)
        foreignClassLoader.close()
    }
}
