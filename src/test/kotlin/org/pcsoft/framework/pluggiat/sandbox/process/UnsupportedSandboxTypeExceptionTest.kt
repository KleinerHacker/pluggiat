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

package org.pcsoft.framework.pluggiat.sandbox.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [UnsupportedSandboxTypeException]'s message formatting: it names the offending method and
 * the given reason, so a host can tell which extension method can never be called on a process-isolated
 * plugin and why.
 */
class UnsupportedSandboxTypeExceptionTest {

    /** Stand-in method used only to obtain a real [java.lang.reflect.Method] instance. */
    private interface ExampleExtension {
        fun methodWithUnsupportedSignature(input: Map<String, String>)
    }

    /**
     * Use case: the exception message names the declaring class, the method name and the given reason.
     */
    @Test
    fun `message names the method and the reason`() {
        val method = ExampleExtension::class.java.getMethod("methodWithUnsupportedSignature", Map::class.java)

        val exception = UnsupportedSandboxTypeException(method, "Map is not a supported type")

        val message = exception.message!!
        assertTrue(message.contains(ExampleExtension::class.java.name))
        assertTrue(message.contains("methodWithUnsupportedSignature"))
        assertTrue(message.contains("Map is not a supported type"))
    }
}
