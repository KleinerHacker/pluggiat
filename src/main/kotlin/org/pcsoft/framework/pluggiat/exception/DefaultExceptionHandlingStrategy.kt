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

package org.pcsoft.framework.pluggiat.exception

import kotlin.reflect.KClass

/**
 * Configurable [ExceptionHandlingStrategy] resolving an [ExceptionHandlingAction] by walking up a
 * `Throwable`'s class hierarchy against [matrix]: the most specific superclass of the actual
 * exception class that has an entry in [matrix] wins.
 *
 * If no class in the hierarchy has an entry, resolution falls back to [parent] (if configured), and
 * finally to the standard rule: a checked exception (any `Exception` that is not a
 * `RuntimeException`) resolves to `IGNORE`, everything else (`RuntimeException`, `Error`, any other
 * `Throwable`) resolves to `UNLOAD`.
 *
 * @property matrix explicit action per exception class
 * @property parent an optional strategy consulted before falling back to the standard rule, to
 * allow custom strategies to nest/chain
 */
class DefaultExceptionHandlingStrategy(
    private val matrix: Map<KClass<out Throwable>, ExceptionHandlingAction> = STANDARD_MATRIX,
    private val parent: ExceptionHandlingStrategy? = null,
) : ExceptionHandlingStrategy {

    override fun resolve(throwable: Throwable): ExceptionHandlingAction {
        lookup(throwable::class, matrix)?.let { return it }
        parent?.let { return it.resolve(throwable) }
        return if (throwable is Exception && throwable !is RuntimeException) {
            ExceptionHandlingAction.IGNORE
        } else {
            ExceptionHandlingAction.UNLOAD
        }
    }

    private fun lookup(kClass: KClass<*>, matrix: Map<KClass<out Throwable>, ExceptionHandlingAction>): ExceptionHandlingAction? {
        var current: Class<*>? = kClass.java
        while (current != null) {
            matrix[current.kotlin]?.let { return it }
            current = current.superclass
        }
        return null
    }

    companion object {
        /**
         * `PluginExecutionException` -> `IGNORE`, `PluginFatalException` -> `UNLOAD`; every other
         * exception class is resolved by the standard checked-vs-unchecked rule (see class KDoc).
         */
        val STANDARD_MATRIX: Map<KClass<out Throwable>, ExceptionHandlingAction> = mapOf(
            PluginExecutionException::class to ExceptionHandlingAction.IGNORE,
            PluginFatalException::class to ExceptionHandlingAction.UNLOAD,
        )
    }
}
