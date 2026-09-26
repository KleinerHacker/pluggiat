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
            SandboxGuardRegistry.unregister(classLoader)
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
            SandboxGuardRegistry.unregister(classLoader)
        }
    }

    /**
     * Use case: [SandboxGuardRegistry.check] for a class loader with no registration at all (not a
     * plugin class, or its plugin was already deactivated) is a silent no-op.
     */
    @Test
    fun `check is a no-op for an unregistered class loader`() {
        SandboxGuardRegistry.check(Marker::class.java, SandboxApiCategory.PROCESS_START)
    }
}
