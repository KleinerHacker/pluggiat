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

package org.pcsoft.framework.pluggiat.sandbox.process.der

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the content-based equality of [SandboxValue.BytesValue], the only [SandboxValue] case whose
 * payload is an array and therefore needs its own `equals`/`hashCode`.
 */
class SandboxValueTest {

    /**
     * Use case: two [SandboxValue.BytesValue] instances wrapping different arrays with the same content
     * are equal and share a hash code, so a decoded value can be compared with the original.
     */
    @Test
    fun `bytes values with the same content are equal`() {
        val first = SandboxValue.BytesValue(byteArrayOf(1, 2, 3))
        val second = SandboxValue.BytesValue(byteArrayOf(1, 2, 3))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    /**
     * Use case: bytes values with different content, or compared with a value of another type, are not
     * equal.
     */
    @Test
    fun `bytes values with different content or type are not equal`() {
        val bytes = SandboxValue.BytesValue(byteArrayOf(1, 2, 3))

        assertNotEquals(bytes, SandboxValue.BytesValue(byteArrayOf(1, 2, 4)))
        assertNotEquals(bytes, SandboxValue.StringValue("abc"))
    }
}
