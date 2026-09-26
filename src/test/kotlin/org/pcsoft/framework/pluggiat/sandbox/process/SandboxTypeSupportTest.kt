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

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureApi

/**
 * Verifies [SandboxTypeSupport.requireSupported]'s signature check in isolation, without any
 * subprocess/socket involved.
 */
class SandboxTypeSupportTest {

    /**
     * Use case: a method built entirely from the supported ASN.1 type set (`String`, `Int`, `Long`,
     * `Unit`) passes [SandboxTypeSupport.requireSupported] without throwing.
     */
    @Test
    fun `supported method signatures pass the check`() {
        val echo = ProcessIsolationFixtureApi::class.java.getMethod("echo", String::class.java)
        val add = ProcessIsolationFixtureApi::class.java.getMethod("add", Int::class.java, Int::class.java)
        val hang = ProcessIsolationFixtureApi::class.java.getMethod("hang")

        assertDoesNotThrow { SandboxTypeSupport.requireSupported(echo) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(add) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(hang) }
    }

    /**
     * Use case: a method with a `Map` parameter (not part of the IP-04 type set) throws
     * [UnsupportedSandboxTypeException].
     */
    @Test
    fun `unsupported parameter type throws UnsupportedSandboxTypeException`() {
        val unsupported = ProcessIsolationFixtureApi::class.java.getMethod("unsupported", Map::class.java)

        assertThrows(UnsupportedSandboxTypeException::class.java) { SandboxTypeSupport.requireSupported(unsupported) }
    }

    /**
     * Use case: [SandboxTypeSupport.encode]/[SandboxTypeSupport.decode] round-trip every supported
     * plain-JVM value shape.
     */
    @Test
    fun `encode and decode round-trip supported values`() {
        assertDoesNotThrow {
            require(SandboxTypeSupport.decode(SandboxTypeSupport.encode(42), Int::class.java) == 42)
            require(SandboxTypeSupport.decode(SandboxTypeSupport.encode("hi"), String::class.java) == "hi")
            require(SandboxTypeSupport.decode(SandboxTypeSupport.encode(true), Boolean::class.java) == true)
        }
    }
}
