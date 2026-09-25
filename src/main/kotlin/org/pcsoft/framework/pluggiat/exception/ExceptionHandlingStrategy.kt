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

/**
 * Action taken in response to a plugin runtime exception, as resolved by an
 * [ExceptionHandlingStrategy].
 */
enum class ExceptionHandlingAction {
    /** The plugin stays active, the incident is only logged/reported. */
    IGNORE,

    /** The plugin is forcibly disabled and its class loader is discarded. */
    UNLOAD,

    /** The whole host JVM is halted. Deliberately not recommended as a general default. */
    CRASH,
}

/**
 * Host-wide strategy resolving which [ExceptionHandlingAction] applies to a `Throwable` that
 * escaped a plugin extension call.
 *
 * Exactly one instance is configured for the whole framework, via
 * `org.pcsoft.framework.pluggiat.PluginManagerConfiguration.exceptionHandlingStrategy`; there is no way
 * to register a per-plugin strategy. A plugin only influences the outcome indirectly, through the
 * class of the exception it lets escape.
 */
fun interface ExceptionHandlingStrategy {

    /**
     * Resolves the [ExceptionHandlingAction] for [throwable].
     */
    fun resolve(throwable: Throwable): ExceptionHandlingAction
}
