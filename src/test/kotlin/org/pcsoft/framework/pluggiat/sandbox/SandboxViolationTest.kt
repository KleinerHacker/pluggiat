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

package org.pcsoft.framework.pluggiat.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies [SandboxViolation]'s plain data holder behavior: it exposes exactly the fields it was
 * constructed with, for both an attributable and a non-attributable (e.g. timeout) violation.
 */
class SandboxViolationTest {

    /**
     * Use case: a violation attributable to a single [SandboxApiCategory] exposes that category and
     * the given plugin id and reason unchanged.
     */
    @Test
    fun `exposes plugin id, category and reason for a category-attributable violation`() {
        val violation = SandboxViolation(
            pluginId = "example-plugin",
            category = SandboxApiCategory.FILESYSTEM,
            reason = "Blocked FILESYSTEM access",
        )

        assertEquals("example-plugin", violation.pluginId)
        assertEquals(SandboxApiCategory.FILESYSTEM, violation.category)
        assertEquals("Blocked FILESYSTEM access", violation.reason)
    }

    /**
     * Use case: a violation not attributable to any single category (e.g. a timeout) carries a `null`
     * category instead of forcing a fake one.
     */
    @Test
    fun `allows a null category for a non-attributable violation`() {
        val violation = SandboxViolation(
            pluginId = "example-plugin",
            category = null,
            reason = "Call timed out",
        )

        assertNull(violation.category)
    }

    /**
     * Use case: two violations built from the same values compare equal, as expected of a data class
     * used for assertions and logging.
     */
    @Test
    fun `two violations with equal fields are equal`() {
        val first = SandboxViolation("example-plugin", SandboxApiCategory.NETWORK, "reason")
        val second = SandboxViolation("example-plugin", SandboxApiCategory.NETWORK, "reason")

        assertEquals(first, second)
    }
}
