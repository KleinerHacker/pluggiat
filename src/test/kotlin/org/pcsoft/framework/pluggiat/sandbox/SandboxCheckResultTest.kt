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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies [SandboxCheckResult]'s two variants: the singleton [SandboxCheckResult.Success] and the
 * reason-carrying [SandboxCheckResult.Failure].
 */
class SandboxCheckResultTest {

    /**
     * Use case: [SandboxCheckResult.Success] is a singleton `data object` - every reference to it is
     * the same instance, so callers can compare with `===`/`assertSame` instead of `equals`.
     */
    @Test
    fun `success is a singleton instance`() {
        assertSame(SandboxCheckResult.Success, SandboxCheckResult.Success)
    }

    /**
     * Use case: [SandboxCheckResult.Failure] exposes the reason it was constructed with unchanged.
     */
    @Test
    fun `failure exposes its reason`() {
        val failure = SandboxCheckResult.Failure("agent not active")

        assertEquals("agent not active", failure.reason)
    }

    /**
     * Use case: two failures built with the same reason compare equal, as expected of a data class.
     */
    @Test
    fun `two failures with equal reason are equal`() {
        assertEquals(SandboxCheckResult.Failure("same reason"), SandboxCheckResult.Failure("same reason"))
    }
}
