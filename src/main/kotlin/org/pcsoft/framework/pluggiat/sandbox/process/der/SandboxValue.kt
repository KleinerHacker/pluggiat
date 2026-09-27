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

/**
 * The minimal, deliberately closed set of parameter/return value shapes IP-04's process isolation
 * IPC can transport across the subprocess boundary, encoded as ASN.1 DER via [DerCodec] (Bouncy
 * Castle `org.bouncycastle.asn1.*`).
 *
 * This is a hard, permanent limitation of process-isolated plugins, not a temporary gap: an
 * extension method whose parameter or return type does not map onto one of these cases (a generic
 * type parameter that is not itself one of these cases, a `Map`, ...) can never be called across the
 * process boundary. [org.pcsoft.framework.pluggiat.sandbox.process.SandboxTypeSupport]
 * detects this ahead of time and [org.pcsoft.framework.pluggiat.sandbox.process.UnsupportedSandboxTypeException]
 * is thrown immediately at the proxy call site - the subprocess is never contacted for such a call.
 *
 * @see DerCodec
 */
sealed interface SandboxValue {
    /** A 32-bit integer parameter/return value. */
    data class IntValue(val value: Int) : SandboxValue

    /** A 64-bit integer parameter/return value. */
    data class LongValue(val value: Long) : SandboxValue

    /** A boolean parameter/return value. */
    data class BooleanValue(val value: Boolean) : SandboxValue

    /** A raw byte array parameter/return value. */
    data class BytesValue(val value: ByteArray) : SandboxValue {
        override fun equals(other: Any?): Boolean = other is BytesValue && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    /** A UTF-8 string parameter/return value. */
    data class StringValue(val value: String) : SandboxValue

    /** A homogeneous list of one of the other, non-[ListValue] [SandboxValue] cases. */
    data class ListValue(val values: List<SandboxValue>) : SandboxValue

    /**
     * A complex object's fields, keyed by field name - a Kotlin data class instance
     * (see [org.pcsoft.framework.pluggiat.sandbox.process.SandboxTypeSupport]), encoded as an ASN.1
     * `SET` whose elements are each a `SEQUENCE { fieldName UTF8String, fieldValue Value }`. A field's
     * value may itself recursively be an [ObjectValue] or [ListValue].
     */
    data class ObjectValue(val fields: Map<String, SandboxValue>) : SandboxValue

    /** The `Unit`/`void` return value - carries no payload. */
    data object UnitValue : SandboxValue
}
