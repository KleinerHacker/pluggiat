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

package org.pcsoft.framework.pluggiat.sandbox.process.fixture

/**
 * A supported complex-object parameter/return value - a data class recursively built from the IP-04
 * supported type set (here: `String`, `Int`, `List<String>`).
 */
data class FixturePerson(
    val name: String,
    val age: Int,
    val nicknames: List<String>,
)

/**
 * Test-only extension point API used by `ProcessIsolationStrategyTest` - a mix of supported (IP-04
 * ASN.1 type set) and one deliberately unsupported method signature.
 */
interface ProcessIsolationFixtureApi {
    /** Supported: `String` parameter/return. */
    fun echo(text: String): String

    /** Supported: `Int` parameters, `Long` return. */
    fun add(a: Int, b: Int): Long

    /** Supported: no parameters, `Unit` return - sleeps far longer than any test timeout. */
    fun hang()

    /** Supported: no parameters, `Unit` return - halts the subprocess JVM immediately. */
    fun crash()

    /** Supported: [FixturePerson] complex-object parameter/return (ASN.1 SET/SEQUENCE). */
    fun birthday(person: FixturePerson): FixturePerson

    /** Unsupported: `Map` is not part of the IP-04 type set. */
    fun unsupported(values: Map<String, String>): String
}

/**
 * Test-only implementation of [ProcessIsolationFixtureApi], instantiated *inside the subprocess* by
 * `ProcessIpcServer` - never in the host JVM.
 */
class ProcessIsolationFixtureImpl : ProcessIsolationFixtureApi {
    override fun echo(text: String): String = text

    override fun add(a: Int, b: Int): Long = (a + b).toLong()

    override fun hang() {
        Thread.sleep(60_000)
    }

    override fun crash() {
        Runtime.getRuntime().halt(1)
    }

    override fun birthday(person: FixturePerson): FixturePerson = person.copy(age = person.age + 1)

    override fun unsupported(values: Map<String, String>): String = values.toString()
}
