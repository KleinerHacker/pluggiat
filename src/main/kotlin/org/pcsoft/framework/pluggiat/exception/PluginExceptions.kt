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
 * Recommended (not mandatory) exception for a plugin to throw when an extension call fails in a
 * way that should not disable the plugin. Resolves to [ExceptionHandlingAction.IGNORE] under
 * [DefaultExceptionHandlingStrategy].
 *
 * Deliberately a `RuntimeException`: the enforcement proxy (see
 * `org.pcsoft.framework.pluggiat.proxy.ExtensionProxyFactory`) re-throws it from a JDK dynamic
 * [java.lang.reflect.Proxy] invocation handler, which wraps any checked exception not declared by
 * the invoked interface method into `UndeclaredThrowableException` - an unchecked type avoids that.
 */
class PluginExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Recommended (not mandatory) exception for a plugin to throw when an extension call fails in a
 * way that should disable the plugin. Resolves to [ExceptionHandlingAction.UNLOAD] under
 * [DefaultExceptionHandlingStrategy].
 *
 * Deliberately a `RuntimeException`, for the same reason as [PluginExecutionException].
 */
class PluginFatalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
