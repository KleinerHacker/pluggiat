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

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Object
import org.bouncycastle.asn1.ASN1OutputStream
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERUTF8String
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger

/**
 * A single extension method invocation, sent host -> subprocess over
 * [org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcClient]/[org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcServer].
 *
 * @property implementationClassName FQCN of the plugin's extension implementation to invoke,
 * instantiated (and cached) lazily inside the subprocess on first use - never resolved/instantiated
 * in the host, see [org.pcsoft.framework.pluggiat.sandbox.process.ProcessIsolationStrategy]
 * @property methodName name of the extension-point-interface method to invoke
 * @property arguments the call's already-[SandboxValue]-encoded arguments, in declaration order
 */
data class ProcessCall(
    val implementationClassName: String,
    val methodName: String,
    val arguments: List<SandboxValue>,
)

/**
 * One [ProcessCall] as it arrived over the IPC socket, together with the [token] the caller presented.
 *
 * The token is kept out of [ProcessCall] itself on purpose: it authenticates the *connection*, not the
 * call, and no caller building a call should have to know it - see
 * [org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcServer] for how it is verified.
 *
 * @property token the shared secret the caller sent as the first field of the message
 * @property call the call itself, valid only if [token] checks out
 */
data class AuthenticatedProcessCall(
    val token: String,
    val call: ProcessCall,
)

/**
 * The reply to one [ProcessCall], sent subprocess -> host.
 */
sealed interface ProcessResponse {
    /** The call completed normally, with [value] as its (possibly [SandboxValue.UnitValue]) return value. */
    data class Success(val value: SandboxValue) : ProcessResponse

    /**
     * The call's target method raised a `Throwable` inside the subprocess.
     *
     * @property message the throwable's message (or its class name if it had none)
     */
    data class Failure(val message: String) : ProcessResponse
}

/**
 * ASN.1 BER encoder/decoder (Bouncy Castle `org.bouncycastle.asn1.*`, `bcprov-jdk18on`) for
 * [ProcessCall]/[ProcessResponse] messages exchanged over IP-04's process-isolation IPC socket.
 *
 * Deliberately minimal, closed ASN.1 type vocabulary (see [SandboxValue]): every value is
 * length-tagged with an explicit [ValueTag] `ASN1Integer` ahead of its payload rather than relying
 * on the payload's own concrete ASN.1 type, so decoding a [SandboxValue.IntValue] vs.
 * [SandboxValue.LongValue] (both backed by [ASN1Integer]) is unambiguous. Every encoded message is a
 * self-delimiting `DERSequence` (ASN.1 BER/DER TLV encoding carries its own length octets), so the
 * wire format needs no separate length prefix - see
 * [org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcClient]/[org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcServer]
 * for how one message is read off a socket's `InputStream` via [ASN1InputStream].
 */
object BerCodec {
    /**
     * Upper bound for a single decoded IPC message, handed to [ASN1InputStream] so a malformed or
     * hostile length header cannot make the decoder allocate arbitrarily much memory before the
     * message is even understood. Without a limit, one message claiming a multi-gigabyte octet string
     * is enough to take the host (or the subprocess) down with an `OutOfMemoryError` - a denial of
     * service that needs no valid token and no valid plugin at all. 16 MiB is far above any realistic
     * extension call payload and far below what would threaten a JVM.
     */
    const val MAX_MESSAGE_SIZE_BYTES: Int = 16 * 1024 * 1024

    /** Discriminator tag written ahead of every [SandboxValue]'s payload, see [BerCodec]. */
    private enum class ValueTag(val code: Int) {
        INT(0), LONG(1), BOOLEAN(2), BYTES(3), STRING(4), LIST(5), UNIT(6);

        companion object {
            /**
             * @throws IllegalArgumentException if [code] is not one of this closed vocabulary's tags -
             * an unknown tag is a malformed (or forged) message, never something to guess at
             */
            fun of(code: Int): ValueTag = entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Malformed SandboxValue: unknown value tag $code")
        }
    }

    // region SandboxValue

    private fun encodeValue(value: SandboxValue): ASN1Object {
        val (tag, payload) = when (value) {
            is SandboxValue.IntValue -> ValueTag.INT to ASN1Integer(value.value.toLong())
            is SandboxValue.LongValue -> ValueTag.LONG to ASN1Integer(value.value)
            is SandboxValue.BooleanValue -> ValueTag.BOOLEAN to ASN1Boolean.getInstance(value.value)
            is SandboxValue.BytesValue -> ValueTag.BYTES to DEROctetString(value.value)
            is SandboxValue.StringValue -> ValueTag.STRING to DERUTF8String(value.value)
            is SandboxValue.ListValue -> ValueTag.LIST to DERSequence(value.values.map(::encodeValue).toTypedArray())
            SandboxValue.UnitValue -> ValueTag.UNIT to DERSequence()
        }
        return DERSequence(arrayOf(ASN1Integer(tag.code.toLong()), payload))
    }

    private fun decodeValue(obj: ASN1Object): SandboxValue {
        val sequence = ASN1Sequence.getInstance(obj)
        // SECURITY: the element count is fixed; a message with more or fewer fields is rejected rather than
        // SECURITY: partially interpreted.
        require(sequence.size() == 2) { "Malformed SandboxValue: expected 2 elements, got ${sequence.size()}" }
        // SECURITY: an unknown tag throws instead of being ignored - the vocabulary is closed, so anything
        // SECURITY: outside it is a malformed or forged message.
        val tag = ValueTag.of(ASN1Integer.getInstance(sequence.getObjectAt(0)).value.toInt())
        val payload = sequence.getObjectAt(1).toASN1Primitive()
        return when (tag) {
            ValueTag.INT -> SandboxValue.IntValue(ASN1Integer.getInstance(payload).value.toInt())
            ValueTag.LONG -> SandboxValue.LongValue(ASN1Integer.getInstance(payload).value.toLong())
            ValueTag.BOOLEAN -> SandboxValue.BooleanValue(ASN1Boolean.getInstance(payload).isTrue)
            ValueTag.BYTES -> SandboxValue.BytesValue(DEROctetString.getInstance(payload).octets)
            ValueTag.STRING -> SandboxValue.StringValue(DERUTF8String.getInstance(payload).string)
            ValueTag.LIST -> SandboxValue.ListValue(ASN1Sequence.getInstance(payload).map { decodeValue(it.toASN1Primitive()) })
            ValueTag.UNIT -> SandboxValue.UnitValue
        }
    }

    // endregion

    // region ProcessCall / ProcessResponse

    private const val RESPONSE_TAG_SUCCESS = 0L
    private const val RESPONSE_TAG_FAILURE = 1L

    /**
     * Encodes [call] as a self-delimiting ASN.1 BER `DERSequence` and writes it to [output], with
     * [token] as the message's **first** field.
     *
     * The token goes first so the receiver can authenticate the sender before interpreting anything
     * else - above all before resolving a class name or instantiating anything from it.
     */
    fun writeCall(call: ProcessCall, token: String, output: OutputStream) {
        val vector = ASN1EncodableVector()
        vector.add(DERUTF8String(token))
        vector.add(DERUTF8String(call.implementationClassName))
        vector.add(DERUTF8String(call.methodName))
        vector.add(DERSequence(call.arguments.map(::encodeValue).toTypedArray()))
        writeSequence(DERSequence(vector), output)
    }

    /**
     * Reads and decodes one message previously written by [writeCall] from [input], as the presented
     * token plus the call itself. Verifying the token is the caller's job (see
     * [org.pcsoft.framework.pluggiat.sandbox.process.ProcessIpcServer]).
     */
    fun readCall(input: InputStream): AuthenticatedProcessCall {
        val sequence = ASN1Sequence.getInstance(readSequence(input))
        require(sequence.size() == 4) { "Malformed ProcessCall: expected 4 elements, got ${sequence.size()}" }
        // SECURITY: the token is the first field, so the receiver can authenticate before interpreting a class
        // SECURITY: name or a method name from the rest of the message.
        val token = DERUTF8String.getInstance(sequence.getObjectAt(0)).string
        val implementationClassName = DERUTF8String.getInstance(sequence.getObjectAt(1)).string
        val methodName = DERUTF8String.getInstance(sequence.getObjectAt(2)).string
        val arguments = ASN1Sequence.getInstance(sequence.getObjectAt(3)).map { decodeValue(it.toASN1Primitive()) }
        return AuthenticatedProcessCall(token, ProcessCall(implementationClassName, methodName, arguments))
    }

    /** Encodes [response] as a self-delimiting ASN.1 BER `DERSequence` and writes it to [output]. */
    fun writeResponse(response: ProcessResponse, output: OutputStream) {
        val vector = ASN1EncodableVector()
        when (response) {
            is ProcessResponse.Success -> {
                vector.add(ASN1Integer(RESPONSE_TAG_SUCCESS))
                vector.add(encodeValue(response.value))
            }

            is ProcessResponse.Failure -> {
                vector.add(ASN1Integer(RESPONSE_TAG_FAILURE))
                vector.add(DERUTF8String(response.message))
            }
        }
        writeSequence(DERSequence(vector), output)
    }

    /** Reads and decodes one [ProcessResponse] previously written by [writeResponse] from [input]. */
    fun readResponse(input: InputStream): ProcessResponse {
        val sequence = ASN1Sequence.getInstance(readSequence(input))
        require(sequence.size() == 2) { "Malformed ProcessResponse: expected 2 elements, got ${sequence.size()}" }
        val tag = ASN1Integer.getInstance(sequence.getObjectAt(0)).value
        return when (tag) {
            BigInteger.valueOf(RESPONSE_TAG_SUCCESS) -> ProcessResponse.Success(decodeValue(sequence.getObjectAt(1).toASN1Primitive()))
            BigInteger.valueOf(RESPONSE_TAG_FAILURE) -> ProcessResponse.Failure(DERUTF8String.getInstance(sequence.getObjectAt(1)).string)
            else -> error("Malformed ProcessResponse: unknown tag $tag")
        }
    }

    // endregion

    /**
     * Writes [sequence] to [output] without closing it - the socket connection carrying multiple
     * messages (or a caller-managed stream in tests) must stay open past a single [writeCall]/
     * [writeResponse] call, unlike [ASN1OutputStream.close]'s default behavior of closing its
     * underlying stream too.
     */
    private fun writeSequence(sequence: DERSequence, output: OutputStream) {
        val asn1Output = ASN1OutputStream.create(output)
        asn1Output.writeObject(sequence)
        output.flush()
    }

    /**
     * Reads exactly one ASN.1 object off [input], bounded by [MAX_MESSAGE_SIZE_BYTES]: the limit makes
     * [ASN1InputStream] reject a length header that claims more than that instead of trying to
     * allocate it.
     */
    private fun readSequence(input: InputStream): ASN1Object {
        // SECURITY: the size limit is what stops a forged length header from being allocated - without it one
        // SECURITY: message claiming gigabytes ends the process with an OutOfMemoryError.
        val asn1Input = ASN1InputStream(input, MAX_MESSAGE_SIZE_BYTES)
        return asn1Input.readObject() as? ASN1Object
            ?: throw java.io.EOFException("No further ASN.1 object on the process IPC stream (peer closed the connection)")
    }
}
