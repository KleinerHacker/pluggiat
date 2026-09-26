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

import org.pcsoft.framework.pluggiat.sandbox.process.ber.SandboxValue
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Checks an extension-point interface [Method]'s signature against IP-04's minimal, closed ASN.1
 * type vocabulary (see [SandboxValue]) and converts between plain JVM values and [SandboxValue] -
 * the single source of truth [ProcessIsolationStrategy]'s proxy `InvocationHandler` consults
 * *before* ever contacting the subprocess, so an unsupported signature throws
 * [UnsupportedSandboxTypeException] locally instead of failing inside the subprocess (see
 * [org.pcsoft.framework.pluggiat.sandbox.process.ber.BerCodec]).
 *
 * Supported: `Int`/`Integer`, `Long`, `Boolean`, `ByteArray`/`byte[]`, `String`, `Unit`/`void`, and a
 * `List<T>` of any of the former (`List` itself, not a narrower/wider `Collection` subtype, and not
 * nested another level deep).
 */
object SandboxTypeSupport {
    /**
     * @throws UnsupportedSandboxTypeException if [method]'s return type or any parameter type is not
     * one of the supported cases
     */
    fun requireSupported(method: Method) {
        unsupportedReason(method.genericReturnType)?.let {
            throw UnsupportedSandboxTypeException(method, "return type ${method.genericReturnType} is unsupported ($it)")
        }
        for ((index, type) in method.genericParameterTypes.withIndex()) {
            unsupportedReason(type)?.let {
                throw UnsupportedSandboxTypeException(method, "parameter #$index type $type is unsupported ($it)")
            }
        }
    }

    /** `null` if [type] is supported, otherwise a short human-readable reason it is not. */
    private fun unsupportedReason(type: Type): String? = when {
        type == Void.TYPE || type == Unit::class.java -> null
        type == Integer.TYPE || type == Integer::class.java -> null
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> null
        type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> null
        type == ByteArray::class.java -> null
        type == String::class.java -> null
        type is ParameterizedType && (type.rawType == List::class.java) -> {
            val elementType = type.actualTypeArguments.getOrNull(0)
            when {
                elementType == null -> "raw List element type"
                elementType is Class<*> && elementType != Any::class.java && unsupportedReason(elementType) == null -> null
                else -> "unsupported List element type $elementType"
            }
        }

        else -> "not part of the IP-04 supported type set"
    }

    /** Encodes a plain JVM [value] (as passed into a proxy call) into its [SandboxValue] wire form. */
    fun encode(value: Any?): SandboxValue = when (value) {
        null -> SandboxValue.UnitValue
        is Int -> SandboxValue.IntValue(value)
        is Long -> SandboxValue.LongValue(value)
        is Boolean -> SandboxValue.BooleanValue(value)
        is ByteArray -> SandboxValue.BytesValue(value)
        is String -> SandboxValue.StringValue(value)
        is List<*> -> SandboxValue.ListValue(value.map(::encode))
        else -> throw IllegalArgumentException("Value of type ${value::class.java} is not encodable as a SandboxValue")
    }

    /** Decodes [value] back into a plain JVM value for [returnType] (a method's generic return type). */
    fun decode(value: SandboxValue, returnType: Type): Any? = when (value) {
        is SandboxValue.IntValue -> value.value
        is SandboxValue.LongValue -> value.value
        is SandboxValue.BooleanValue -> value.value
        is SandboxValue.BytesValue -> value.value
        is SandboxValue.StringValue -> value.value
        is SandboxValue.ListValue -> {
            val elementType = (returnType as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) ?: Any::class.java
            value.values.map { decode(it, elementType) }
        }

        SandboxValue.UnitValue -> if (returnType == Unit::class.java || returnType == Void.TYPE) Unit else null
    }
}
