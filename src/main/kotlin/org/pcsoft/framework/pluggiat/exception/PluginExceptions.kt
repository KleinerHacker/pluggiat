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
