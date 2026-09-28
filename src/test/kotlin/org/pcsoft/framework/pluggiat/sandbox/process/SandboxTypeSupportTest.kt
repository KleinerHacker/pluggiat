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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    /** A supported data class used as a nested field. */
    data class SampleAddress(val city: String)

    /** A supported data class combining every scalar shape, a list and a nested data class. */
    data class SampleRecord(
        val label: String,
        val count: Long,
        val flag: Boolean,
        val blob: ByteArray,
        val tags: List<String>,
        val address: SampleAddress,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SampleRecord

            if (count != other.count) return false
            if (flag != other.flag) return false
            if (label != other.label) return false
            if (!blob.contentEquals(other.blob)) return false
            if (tags != other.tags) return false
            if (address != other.address) return false

            return true
        }

        override fun hashCode(): Int {
            var result = count.hashCode()
            result = 31 * result + flag.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + blob.contentHashCode()
            result = 31 * result + tags.hashCode()
            result = 31 * result + address.hashCode()
            return result
        }
    }

    /** Test-only interface, only used to obtain `Method` instances with the parameter types above. */
    private interface TestApi {
        fun notADataClass(value: NotADataClass): String
        fun unsupportedField(value: PersonWithUnsupportedField): String
        fun scalars(count: Long, flag: Boolean, blob: ByteArray): Boolean
        fun lists(values: List<String>): List<Int>
        fun nothing()
        fun unsupportedReturn(): Map<String, String>
        fun unsupportedSecondParameter(text: String, values: Map<String, String>): String
        fun unsupportedListElement(values: List<Map<String, String>>): String
        fun anyListElement(values: List<Any>): String
        fun record(value: SampleRecord): SampleRecord
    }

    private fun method(name: String) = TestApi::class.java.methods.first { it.name == name }

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
     * Use case: `Long`, `Boolean` and `ByteArray` parameters, `List<String>` parameters, `List<Int>`
     * return values, `void` returns and data classes nesting all of them pass the check.
     */
    @Test
    fun `scalar list and nested data class signatures pass the check`() {
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(method("scalars")) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(method("lists")) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(method("nothing")) }
        assertDoesNotThrow { SandboxTypeSupport.requireSupported(method("record")) }
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
     * Use case: a method whose return type is outside the vocabulary is rejected with an exception that
     * names the return type.
     */
    @Test
    fun `an unsupported return type is rejected`() {
        val exception = assertThrows(UnsupportedSandboxTypeException::class.java) {
            SandboxTypeSupport.requireSupported(method("unsupportedReturn"))
        }

        assertEquals(true, exception.message!!.contains("return type"))
    }

    /**
     * Use case: an unsupported type at a later parameter position is found and reported with its index.
     */
    @Test
    fun `an unsupported second parameter is rejected with its index`() {
        val exception = assertThrows(UnsupportedSandboxTypeException::class.java) {
            SandboxTypeSupport.requireSupported(method("unsupportedSecondParameter"))
        }

        assertEquals(true, exception.message!!.contains("parameter #1"))
    }

    /**
     * Use case: a `List` whose element type is itself unsupported (here: a `Map`, or the open `Any`) is
     * rejected.
     */
    @Test
    fun `a list with an unsupported element type is rejected`() {
        assertThrows(UnsupportedSandboxTypeException::class.java) {
            SandboxTypeSupport.requireSupported(method("unsupportedListElement"))
        }
        assertThrows(UnsupportedSandboxTypeException::class.java) {
            SandboxTypeSupport.requireSupported(method("anyListElement"))
        }
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
     * Use case: `Long` and byte array values are encoded into their dedicated [SandboxValue] cases and
     * decoded back unchanged.
     */
    @Test
    fun `encode and decode round-trip long and byte array values`() {
        assertEquals(SandboxValue.LongValue(7L), SandboxTypeSupport.encode(7L))
        assertEquals(7L, SandboxTypeSupport.decode(SandboxValue.LongValue(7L), Long::class.java))
        val bytes = byteArrayOf(4, 5, 6)
        assertEquals(SandboxValue.BytesValue(bytes), SandboxTypeSupport.encode(bytes))
        assertArrayEquals(bytes, SandboxTypeSupport.decode(SandboxValue.BytesValue(bytes), ByteArray::class.java) as ByteArray)
    }

    /**
     * Use case: a `null` value is encoded as [SandboxValue.UnitValue], the "no payload" case.
     */
    @Test
    fun `null is encoded as a unit value`() {
        assertEquals(SandboxValue.UnitValue, SandboxTypeSupport.encode(null))
    }

    /**
     * Use case: a [SandboxValue.UnitValue] decodes to `Unit` for a `void` return type and to `null` for
     * any other expected type.
     */
    @Test
    fun `a unit value decodes to Unit for void and to null otherwise`() {
        assertEquals(Unit, SandboxTypeSupport.decode(SandboxValue.UnitValue, Void.TYPE))
        assertEquals(Unit, SandboxTypeSupport.decode(SandboxValue.UnitValue, Unit::class.java))
        assertNull(SandboxTypeSupport.decode(SandboxValue.UnitValue, String::class.java))
    }

    /**
     * Use case: a list is encoded element by element and decoded using the generic element type of the
     * method's return type; with a non-parameterized expected type the elements are decoded as they are.
     */
    @Test
    fun `lists are encoded and decoded element by element`() {
        val encoded = SandboxTypeSupport.encode(listOf(1, 2))
        assertEquals(SandboxValue.ListValue(listOf(SandboxValue.IntValue(1), SandboxValue.IntValue(2))), encoded)

        assertEquals(listOf(1, 2), SandboxTypeSupport.decode(encoded, method("lists").genericReturnType))
        assertEquals(listOf(1, 2), SandboxTypeSupport.decode(encoded, List::class.java))
    }

    /**
     * Use case: a value that is neither a supported scalar, list nor data class is refused by
     * [SandboxTypeSupport.encode] instead of being encoded generically.
     */
    @Test
    fun `encoding a value outside the vocabulary is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SandboxTypeSupport.encode(mapOf("a" to "b")) }
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

    /**
     * Use case: a data class combining every scalar shape, a list and a nested data class round-trips
     * through encode and decode field by field.
     */
    @Test
    fun `encode and decode round-trip a record with every supported field shape`() {
        val record = SampleRecord("label", 9L, true, byteArrayOf(1, 2), listOf("a", "b"), SampleAddress("London"))

        val decoded = SandboxTypeSupport.decode(SandboxTypeSupport.encode(record), SampleRecord::class.java) as SampleRecord

        assertEquals(record.label, decoded.label)
        assertEquals(record.count, decoded.count)
        assertEquals(record.flag, decoded.flag)
        assertArrayEquals(record.blob, decoded.blob)
        assertEquals(record.tags, decoded.tags)
        assertEquals(record.address, decoded.address)
    }

    /**
     * Use case: decoding an object value that lacks a field of the target data class fails with an
     * [IllegalArgumentException] naming the missing field instead of constructing a partial object.
     */
    @Test
    fun `decoding an object with a missing field fails`() {
        val incomplete = SandboxValue.ObjectValue(mapOf("wrong" to SandboxValue.StringValue("x")))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SandboxTypeSupport.decode(incomplete, SampleAddress::class.java)
        }

        assertEquals(true, exception.message!!.contains("city"))
    }
}
