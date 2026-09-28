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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Verifies [DerCodec]'s encode/decode round trip for a [ProcessCall]/[ProcessResponse] without any
 * real socket - the pure ASN.1 DER (de)serialization logic in isolation.
 */
class DerCodecTest {

    /**
     * Use case: a [ProcessCall] carrying one of every supported [SandboxValue] case (including a
     * nested [SandboxValue.ListValue]) round-trips through [DerCodec.writeCall]/[DerCodec.readCall]
     * unchanged, together with the IPC token it was written with.
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

        DerCodec.writeCall(call, "test-ipc-token", output)
        val decoded = DerCodec.readCall(ByteArrayInputStream(output.toByteArray()))

        assertEquals("test-ipc-token", decoded.token)
        assertEquals(call, decoded.call)
    }

    /**
     * Use case: an [SandboxValue.ObjectValue] carrying a mix of scalar fields, a nested
     * [SandboxValue.ListValue] field and a nested [SandboxValue.ObjectValue] field round-trips through
     * [DerCodec.writeCall]/[DerCodec.readCall] unchanged - the complex-object SET/SEQUENCE wire format.
     */
    @Test
    fun `ObjectValue with nested fields round-trips through write and read`() {
        val call = ProcessCall(
            implementationClassName = "com.example.Impl",
            methodName = "doSomethingComplex",
            arguments = listOf(
                SandboxValue.ObjectValue(
                    fields = linkedMapOf(
                        "name" to SandboxValue.StringValue("Ada"),
                        "age" to SandboxValue.IntValue(37),
                        "tags" to SandboxValue.ListValue(listOf(SandboxValue.StringValue("x"), SandboxValue.StringValue("y"))),
                        "address" to SandboxValue.ObjectValue(
                            fields = linkedMapOf("city" to SandboxValue.StringValue("London")),
                        ),
                    ),
                ),
            ),
        )
        val output = ByteArrayOutputStream()

        DerCodec.writeCall(call, "test-ipc-token", output)
        val decoded = DerCodec.readCall(ByteArrayInputStream(output.toByteArray()))

        assertEquals(call, decoded.call)
    }

    /**
     * Use case: a message whose value carries a tag outside [DerCodec]'s closed vocabulary is rejected
     * instead of being guessed at - an unknown tag can only come from a malformed or forged message.
     */
    @Test
    fun `an unknown value tag is rejected`() {
        val forged = org.bouncycastle.asn1.DERSequence(
            arrayOf(
                org.bouncycastle.asn1.DERUTF8String("token"),
                org.bouncycastle.asn1.DERUTF8String("com.example.Impl"),
                org.bouncycastle.asn1.DERUTF8String("doSomething"),
                org.bouncycastle.asn1.DERSequence(
                    arrayOf<org.bouncycastle.asn1.ASN1Encodable>(
                        org.bouncycastle.asn1.DERSequence(
                            arrayOf<org.bouncycastle.asn1.ASN1Encodable>(
                                org.bouncycastle.asn1.ASN1Integer(99L),
                                org.bouncycastle.asn1.ASN1Integer(1L),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DerCodec.readCall(ByteArrayInputStream(forged.encoded))
        }
    }

    /**
     * Use case: a message with the wrong number of top-level elements is rejected rather than partially
     * interpreted - the field count of a call is fixed, and a short message must not be read as one
     * whose missing fields simply default.
     */
    @Test
    fun `a call with the wrong element count is rejected`() {
        val forged = org.bouncycastle.asn1.DERSequence(
            arrayOf(
                org.bouncycastle.asn1.DERUTF8String("token"),
                org.bouncycastle.asn1.DERUTF8String("com.example.Impl"),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DerCodec.readCall(ByteArrayInputStream(forged.encoded))
        }
    }

    /**
     * Use case: a message that declares more content than [DerCodec.MAX_MESSAGE_SIZE_BYTES] allows is
     * rejected by the decoder instead of being allocated - a forged length header must not be able to
     * exhaust the heap of whoever reads it.
     */
    @Test
    fun `a message larger than the size limit is rejected`() {
        // A definite-length octet string header claiming far more content than the limit permits, with
        // no content following it: the decoder has to refuse it on the header alone.
        val claimedLength = DerCodec.MAX_MESSAGE_SIZE_BYTES.toLong() + 1
        val header = ByteArrayOutputStream()
        header.write(0x04)
        header.write(0x84)
        header.write(((claimedLength shr 24) and 0xFF).toInt())
        header.write(((claimedLength shr 16) and 0xFF).toInt())
        header.write(((claimedLength shr 8) and 0xFF).toInt())
        header.write((claimedLength and 0xFF).toInt())

        assertThrows(Exception::class.java) {
            DerCodec.readCall(ByteArrayInputStream(header.toByteArray()))
        }
    }

    /**
     * Use case: a successful [ProcessResponse.Success] round-trips through
     * [DerCodec.writeResponse]/[DerCodec.readResponse] unchanged.
     */
    @Test
    fun `ProcessResponse Success round-trips through write and read`() {
        val response = ProcessResponse.Success(SandboxValue.StringValue("result"))
        val output = ByteArrayOutputStream()

        DerCodec.writeResponse(response, output)
        val decoded = DerCodec.readResponse(ByteArrayInputStream(output.toByteArray()))

        assertEquals(response, decoded)
    }

    /**
     * Use case: a failed [ProcessResponse.Failure] round-trips through
     * [DerCodec.writeResponse]/[DerCodec.readResponse] unchanged.
     */
    @Test
    fun `ProcessResponse Failure round-trips through write and read`() {
        val response = ProcessResponse.Failure("boom")
        val output = ByteArrayOutputStream()

        DerCodec.writeResponse(response, output)
        val decoded = DerCodec.readResponse(ByteArrayInputStream(output.toByteArray()))

        assertEquals(response, decoded)
    }
}
