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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.FixturePerson
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureApi

/**
 * Verifies [SandboxTypeSupport.requireSupported]'s signature check in isolation, without any
 * subprocess/socket involved.
 */
class SandboxTypeSupportTest {

    /** A data class not part of the closed vocabulary (no primary constructor matches a supported shape). */
    private class NotADataClass(val value: String)

    /** A data class carrying an unsupported field type (`Map`), used to verify rejection is recursive. */
    private data class PersonWithUnsupportedField(val values: Map<String, String>)

    /**
     * Use case: a method built entirely from the supported ASN.1 type set (`String`, `Int`, `Long`,
     * `Unit`, a complex-object data class) passes [SandboxTypeSupport.requireSupported] without throwing.
     */
    @Test
    fun `supported method signatures pass the check`() {
        val echo = ProcessIsolationFixtureApi::class.java.getMethod("echo", String::class.java)
        val add = ProcessIsolationFixtureApi::class.java.getMethod("add", Int::class.java, Int::class.java)
        val hang = ProcessIsolationFixtureApi::class.java.getMethod("hang")
        val birthday = ProcessIsolationFixtureApi::class.java.getMethod("birthday", FixturePerson::class.java)

        assertDoesNotThrow { SandboxTypeSupport.requireSupported(echo) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(add) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(hang) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(birthday) }
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
     * Use case: a plain (non-data) class is not a supported complex-object type and throws
     * [UnsupportedSandboxTypeException] just like any other type outside the vocabulary.
     */
    @Test
    fun `a non-data class parameter type is unsupported`() {
        val method = TestApi::class.java.getMethod("notADataClass", NotADataClass::class.java)

        assertThrows(UnsupportedSandboxTypeException::class.java) { SandboxTypeSupport.requireSupported(method) }
    }

    /**
     * Use case: a data class with an unsupported field type is rejected recursively - the check must
     * not stop at "it is a data class" but verify every one of its fields too.
     */
    @Test
    fun `a data class with an unsupported field is unsupported`() {
        val method = TestApi::class.java.getMethod("unsupportedField", PersonWithUnsupportedField::class.java)

        assertThrows(UnsupportedSandboxTypeException::class.java) { SandboxTypeSupport.requireSupported(method) }
    }

    /** Test-only interface, only used to obtain `Method` instances with the parameter types above. */
    private interface TestApi {
        fun notADataClass(value: NotADataClass): String
        fun unsupportedField(value: PersonWithUnsupportedField): String
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

    /**
     * Use case: a complex-object data class value round-trips through [SandboxTypeSupport.encode]/
     * [SandboxTypeSupport.decode] as a [SandboxValue.ObjectValue], including its nested `List` field.
     */
    @Test
    fun `encode and decode round-trip a complex object value`() {
        val person = FixturePerson(name = "Ada", age = 36, nicknames = listOf("Countess", "Enchantress"))

        val encoded = SandboxTypeSupport.encode(person)

        assertEquals(SandboxValue.ObjectValue::class.java, encoded::class.java)
        val decoded = SandboxTypeSupport.decode(encoded, FixturePerson::class.java)
        assertEquals(person, decoded)
    }
}
