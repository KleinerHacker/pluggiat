package org.pcsoft.framework.pluggiat.extension

import org.pcsoft.framework.pluggiat.proxy.isProxyEligible
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * One registered extension point: its host-defined configuration class, whether it is exclusive,
 * and the resolved host plugin API type `T` (`null` if it could not be determined via reflection).
 *
 * `T`, whenever it could be determined, is always eligible for the runtime enforcement proxy (task
 * 6) - [ExtensionPointRegistry] rejects the registration outright otherwise, so a stored
 * registration with a non-`null` [apiType] never needs a separate eligibility flag.
 *
 * @property configurationClass the host-defined [ExtensionConfiguration] class for this extension point
 * @property exclusive whether at most one plugin may contribute to this extension point at a time
 * @property apiType the resolved host plugin API type `T`, `null` if it could not be determined via reflection
 */
internal data class ExtensionPointRegistration(
    val configurationClass: KClass<out ExtensionConfiguration<*>>,
    val exclusive: Boolean,
    val apiType: Class<*>?,
)

/**
 * Registry of extension points a host offers to plugins.
 *
 * The host registers the [ExtensionConfiguration] classes it defines; each must be annotated with
 * [ExtensionPoint]. The registry resolves a manifest's `extensions.<key>[]` entries against these
 * registered configuration classes by key.
 *
 * A registered extension point's host plugin API type `T` must be proxy-eligible (interface, or
 * non-final class, see [isProxyEligible]) whenever it can be determined via reflection - extension
 * instances are exclusively handed to the host as a runtime enforcement proxy (IP-06 task 6), so a
 * final `T` can never be loaded at all. This is a registration-time error of the HOST's own
 * [ExtensionConfiguration] declaration, not a late-binding plugin problem, and therefore fails
 * registration itself rather than degrading gracefully per extension point.
 *
 * @param configurationClasses the host's [ExtensionConfiguration] classes, each annotated with [ExtensionPoint]
 * @throws ExtensionRegistrationException if a class is missing the [ExtensionPoint] annotation, a
 * key is registered twice, or a resolved host plugin API type `T` is not proxy-eligible
 */
class ExtensionPointRegistry(configurationClasses: List<KClass<out ExtensionConfiguration<*>>>) {
    private val logger = LoggerFactory.getLogger(ExtensionPointRegistry::class.java)

    private val registrations: Map<String, ExtensionPointRegistration> = buildMap {
        for (configurationClass in configurationClasses) {
            val annotation = configurationClass.findAnnotation<ExtensionPoint>()
                ?: throw ExtensionRegistrationException(
                    "Extension configuration class ${configurationClass.qualifiedName} is not annotated with @ExtensionPoint"
                )
            if (containsKey(annotation.key)) {
                throw ExtensionRegistrationException(
                    "Extension point key '${annotation.key}' is registered more than once"
                )
            }
            val apiType = extensionApiTypeOf(configurationClass)
            if (apiType != null && !isProxyEligible(apiType)) {
                val message = "Host plugin API type ${apiType.name} of extension point '${annotation.key}' is a " +
                    "final class and not eligible for the enforcement proxy; extension point registration is invalid"
                logger.error(message)
                throw ExtensionRegistrationException(message)
            }
            put(annotation.key, ExtensionPointRegistration(configurationClass, annotation.exclusive, apiType))
        }
    }

    internal fun registrationFor(key: String): ExtensionPointRegistration? = registrations[key]

    internal fun isExclusive(key: String): Boolean = registrations[key]?.exclusive ?: false

    private fun extensionApiTypeOf(configurationClass: KClass<out ExtensionConfiguration<*>>): Class<*>? =
        configurationClass.java.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .firstOrNull { it.rawType == ExtensionConfiguration::class.java }
            ?.actualTypeArguments
            ?.firstOrNull() as? Class<*>
}
