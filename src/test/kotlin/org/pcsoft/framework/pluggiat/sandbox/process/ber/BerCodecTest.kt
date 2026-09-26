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

package org.pcsoft.framework.pluggiat.sandbox.process.ber

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Verifies [BerCodec]'s encode/decode round trip for a [ProcessCall]/[ProcessResponse] without any
 * real socket - the pure ASN.1 BER (de)serialization logic in isolation.
 */
class BerCodecTest {

    /**
     * Use case: a [ProcessCall] carrying one of every supported [SandboxValue] case (including a
     * nested [SandboxValue.ListValue]) round-trips through [BerCodec.writeCall]/[BerCodec.readCall]
     * unchanged.
     */
    @Test
    fun `ProcessCall round-trips through write and read`() {
        val call = ProcessCall(
            implementationClassName = "com.example.Impl",
            methodName = "doSomething",
            arguments = listOf(
                SandboxValue.IntValue(42),
                SandboxValue.LongValue(123456789L),
                SandboxValue.BooleanValue(true),
                SandboxValue.BytesValue(byteArrayOf(1, 2, 3)),
                SandboxValue.StringValue("hello"),
                SandboxValue.ListValue(listOf(SandboxValue.StringValue("a"), SandboxValue.StringValue("b"))),
                SandboxValue.UnitValue,
            ),
        )
        val output = ByteArrayOutputStream()

        BerCodec.writeCall(call, output)
        val decoded = BerCodec.readCall(ByteArrayInputStream(output.toByteArray()))

        assertEquals(call, decoded)
    }

    /**
     * Use case: a successful [ProcessResponse.Success] round-trips through
     * [BerCodec.writeResponse]/[BerCodec.readResponse] unchanged.
     */
    @Test
    fun `ProcessResponse Success round-trips through write and read`() {
        val response = ProcessResponse.Success(SandboxValue.StringValue("result"))
        val output = ByteArrayOutputStream()

        BerCodec.writeResponse(response, output)
        val decoded = BerCodec.readResponse(ByteArrayInputStream(output.toByteArray()))

        assertEquals(response, decoded)
    }

    /**
     * Use case: a failed [ProcessResponse.Failure] round-trips through
     * [BerCodec.writeResponse]/[BerCodec.readResponse] unchanged.
     */
    @Test
    fun `ProcessResponse Failure round-trips through write and read`() {
        val response = ProcessResponse.Failure("boom")
        val output = ByteArrayOutputStream()

        BerCodec.writeResponse(response, output)
        val decoded = BerCodec.readResponse(ByteArrayInputStream(output.toByteArray()))

        assertEquals(response, decoded)
    }
}
