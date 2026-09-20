package org.pcsoft.framework.pluggiat.extension

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * One registered extension point: its host-defined configuration class and whether it is exclusive.
 */
internal data class ExtensionPointRegistration(
    val configurationClass: KClass<out ExtensionConfiguration<*>>,
    val exclusive: Boolean,
)

/**
 * Registry of extension points a host offers to plugins.
 *
 * The host registers the [ExtensionConfiguration] classes it defines; each must be annotated with
 * [ExtensionPoint]. The registry resolves a manifest's `extensions.<key>[]` entries against these
 * registered configuration classes by key.
 *
 * @param configurationClasses the host's [ExtensionConfiguration] classes, each annotated with [ExtensionPoint]
 * @throws ExtensionRegistrationException if a class is missing the [ExtensionPoint] annotation or a key is registered twice
 */
class ExtensionPointRegistry(configurationClasses: List<KClass<out ExtensionConfiguration<*>>>) {

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
            put(annotation.key, ExtensionPointRegistration(configurationClass, annotation.exclusive))
        }
    }

    internal fun registrationFor(key: String): ExtensionPointRegistration? = registrations[key]

    internal fun isExclusive(key: String): Boolean = registrations[key]?.exclusive ?: false
}
