package org.pcsoft.framework.pluggiat.proxy

import net.bytebuddy.ByteBuddy
import net.bytebuddy.implementation.InvocationHandlerAdapter
import net.bytebuddy.matcher.ElementMatchers
import org.objenesis.ObjenesisStd
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingAction
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.PluginExecutionException
import org.pcsoft.framework.pluggiat.exception.PluginFatalException
import org.slf4j.LoggerFactory
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type

/**
 * Whether [type] is eligible to be enforced through a runtime proxy: an interface, or a
 * non-primitive, non-final class ("open" from a JVM point of view; Kotlin classes are `final` by
 * default and must be marked `open` explicitly to qualify).
 *
 * @param type the type to check
 */
fun isProxyEligible(type: Class<*>): Boolean =
    !type.isPrimitive && (type.isInterface || !Modifier.isFinal(type.modifiers))

private val NATIVE_PACKAGE_PREFIXES = listOf("java.", "javax.", "kotlin.", "javafx.")

/**
 * Swappable seam for [ExceptionHandlingAction.CRASH]'s JVM halt, so it can be verified in tests
 * without actually killing the test process. Production code never needs to touch this.
 */
object PluginCrashHandler {
    var action: (Throwable) -> Nothing = { throwable ->
        throwable.printStackTrace()
        Runtime.getRuntime().halt(1)
        throw AssertionError("unreachable: Runtime.halt() never returns")
    }
}

private fun isNativeType(type: Class<*>): Boolean =
    type.isPrimitive || type == String::class.java ||
        (Modifier.isFinal(type.modifiers) && NATIVE_PACKAGE_PREFIXES.any { type.name.startsWith(it) })

/**
 * Creates the runtime enforcement proxy for extension instances handed out to the host (task 6 of
 * IP-06's implementation plan).
 *
 * Every call into the real instance is intercepted: any `Throwable` it throws is caught and
 * resolved via [ExceptionHandlingStrategy], and only [PluginExecutionException]/[PluginFatalException]
 * ever escape to the host. Eligible return values (see [isProxyEligible]) are recursively wrapped
 * the same way, including array elements, `Collection` elements and `Map` values.
 */
object ExtensionProxyFactory {
    private val logger = LoggerFactory.getLogger(ExtensionProxyFactory::class.java)
    private val objenesis = ObjenesisStd()

    /**
     * Creates a proxy of static type [type] delegating to [target].
     *
     * @param type the proxy-eligible type to expose (see [isProxyEligible]); an interface uses a JDK
     * dynamic [Proxy], an open class uses a ByteBuddy subclass instantiated via Objenesis
     * @param target the real instance every proxy call is delegated to
     * @param exceptionHandlingStrategy resolves the [ExceptionHandlingAction] for a `Throwable`
     * caught from a delegated call
     * @param onUnload invoked synchronously, before a [PluginFatalException] escapes, whenever
     * [exceptionHandlingStrategy] resolves a thrown `Throwable` to [ExceptionHandlingAction.UNLOAD]
     * @throws IllegalArgumentException if [type] is not [isProxyEligible]
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> create(
        type: Class<T>,
        target: T,
        exceptionHandlingStrategy: ExceptionHandlingStrategy,
        onUnload: () -> Unit,
    ): T {
        require(isProxyEligible(type)) { "Type ${type.name} is not proxy-eligible (must be an interface or a non-final class)" }
        val handler = Interceptor(target, exceptionHandlingStrategy, onUnload)
        return if (type.isInterface) {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
        } else {
            val subclass = ByteBuddy()
                .subclass(type)
                .method(ElementMatchers.isPublic<net.bytebuddy.description.method.MethodDescription>().and(ElementMatchers.not(ElementMatchers.isFinal())))
                .intercept(InvocationHandlerAdapter.of(handler))
                .make()
                .load(type.classLoader)
                .loaded
            objenesis.newInstance(subclass) as T
        }
    }

    private class Interceptor(
        private val target: Any,
        private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
        private val onUnload: () -> Unit,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val result = try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (invocationTarget: InvocationTargetException) {
                throw handle(invocationTarget.targetException ?: invocationTarget)
            } catch (throwable: Throwable) {
                throw handle(throwable)
            }
            return wrapReturnValue(result, method.genericReturnType, exceptionHandlingStrategy, onUnload)
        }

        private fun handle(throwable: Throwable): Throwable {
            // Already the framework's own exception type escaping from a nested proxy call.
            if (throwable is PluginExecutionException || throwable is PluginFatalException) {
                return throwable
            }
            return when (exceptionHandlingStrategy.resolve(throwable)) {
                ExceptionHandlingAction.IGNORE -> PluginExecutionException(throwable.message ?: throwable::class.java.name, throwable)
                ExceptionHandlingAction.UNLOAD -> {
                    logger.error("Plugin extension call raised {}, forcibly unloading the plugin", throwable::class.simpleName, throwable)
                    onUnload()
                    PluginFatalException(throwable.message ?: throwable::class.java.name, throwable)
                }

                ExceptionHandlingAction.CRASH -> {
                    logger.warn("Plugin extension call raised {}, host is configured to CRASH the JVM", throwable::class.simpleName, throwable)
                    PluginCrashHandler.action(throwable)
                }
            }
        }
    }
}

/**
 * Recursively wraps [value] for the given [declaredType] (a method's generic return type), per the
 * rules of [ExtensionProxyFactory.create]. Exposed separately so it can be unit-tested against
 * arbitrary generic types without going through a full proxy invocation.
 *
 * @param value the value to wrap, as returned by the intercepted method; `null` is passed through
 * unchanged
 * @param declaredType the method's declared (generic) return type, used to decide proxy
 * eligibility and to descend into arrays/`Collection`/`Map`
 * @param exceptionHandlingStrategy forwarded to any proxy created for an eligible [value]
 * @param onUnload forwarded to any proxy created for an eligible [value]
 */
fun wrapReturnValue(
    value: Any?,
    declaredType: Type,
    exceptionHandlingStrategy: ExceptionHandlingStrategy,
    onUnload: () -> Unit,
): Any? {
    if (value == null) return value
    val logger = LoggerFactory.getLogger(ExtensionProxyFactory::class.java)

    return when (declaredType) {
        is Class<*> -> when {
            isNativeType(declaredType) -> value
            declaredType.isArray -> wrapArray(value, declaredType.componentType, exceptionHandlingStrategy, onUnload)
            isProxyEligible(declaredType) -> ExtensionProxyFactory.create(
                @Suppress("UNCHECKED_CAST") (declaredType as Class<Any>),
                value,
                exceptionHandlingStrategy,
                onUnload,
            )

            else -> {
                logger.warn(
                    "Return value of type {} is final and not eligible for the enforcement proxy; the security " +
                        "guarantee does not apply to it, the value is passed through unchanged",
                    declaredType.name,
                )
                value
            }
        }

        is ParameterizedType -> wrapParameterized(value, declaredType, exceptionHandlingStrategy, onUnload, logger)
        else -> {
            logger.warn("Return type {} is a raw/wildcard generic type, element eligibility cannot be determined; passed through unchanged", declaredType)
            value
        }
    }
}

private fun wrapArray(value: Any, componentType: Class<*>, exceptionHandlingStrategy: ExceptionHandlingStrategy, onUnload: () -> Unit): Any {
    if (!isProxyEligible(componentType) || isNativeType(componentType)) return value
    val length = ReflectArray.getLength(value)
    val result = ReflectArray.newInstance(componentType, length)
    for (i in 0 until length) {
        val element = ReflectArray.get(value, i)
        ReflectArray.set(result, i, wrapReturnValue(element, componentType, exceptionHandlingStrategy, onUnload))
    }
    return result
}

@Suppress("UNCHECKED_CAST")
private fun wrapParameterized(
    value: Any,
    type: ParameterizedType,
    exceptionHandlingStrategy: ExceptionHandlingStrategy,
    onUnload: () -> Unit,
    logger: org.slf4j.Logger,
): Any {
    val rawType = type.rawType as? Class<*> ?: return value
    return when {
        Collection::class.java.isAssignableFrom(rawType) && value is Collection<*> -> {
            val elementType = type.actualTypeArguments.getOrNull(0)
            if (elementType == null || elementType !is Class<*>) {
                logger.warn("Collection element type of {} is a raw/wildcard generic type; elements are passed through unchanged", rawType.name)
                value
            } else {
                value.map { element -> wrapReturnValue(element, elementType, exceptionHandlingStrategy, onUnload) }
            }
        }

        Map::class.java.isAssignableFrom(rawType) && value is Map<*, *> -> {
            val valueType = type.actualTypeArguments.getOrNull(1)
            if (valueType == null || valueType !is Class<*>) {
                logger.warn("Map value type of {} is a raw/wildcard generic type; values are passed through unchanged", rawType.name)
                value
            } else {
                value.mapValues { (_, v) -> wrapReturnValue(v, valueType, exceptionHandlingStrategy, onUnload) }
            }
        }

        else -> value
    }
}
