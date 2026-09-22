package org.pcsoft.framework.pluggiat.extension

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.pcsoft.framework.pluggiat.manifest.ExtensionEntry
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Maps a single manifest `extensions.<key>[]` entry onto its registered [ExtensionConfiguration] and
 * an instantiated plugin implementation.
 *
 * @property registry the host's extension point registry
 * @property classResolver resolves the entry's `implementation` FQCN to a [Class]; defaults to resolution
 * via the calling class loader, replaceable by an isolated, classloader-aware implementation
 */
internal class ExtensionDecorator(
    private val registry: ExtensionPointRegistry,
    private val classResolver: ExtensionClassResolver = DefaultExtensionClassResolver,
) {
    private val logger = LoggerFactory.getLogger(ExtensionDecorator::class.java)
    private val objectMapper = ObjectMapper().registerModule(kotlinModule())

    /**
     * Resolves and instantiates [entry], contributed by [pluginId] under [key].
     *
     * @throws ExtensionMappingException if [key] is not registered, the entry cannot be mapped onto
     * the registered configuration class, or the implementation class cannot be resolved/instantiated
     */
    fun decorate(pluginId: String, key: String, entry: ExtensionEntry): ResolvedExtension {
        val registration = registry.registrationFor(key)
            ?: throw ExtensionMappingException("No extension point registered for key '$key'")

        val implementationClass = try {
            classResolver.resolve(entry.implementation)
        } catch (e: ClassNotFoundException) {
            throw ExtensionMappingException("Implementation class '${entry.implementation}' could not be resolved", e)
        }

        val configuration = try {
            buildConfiguration(registration.configurationClass, implementationClass, entry.additionalProperties)
        } catch (e: ExtensionMappingException) {
            throw e
        } catch (e: Exception) {
            throw ExtensionMappingException(
                "Extension entry for key '$key' could not be mapped to ${registration.configurationClass.qualifiedName}",
                e,
            )
        }

        val instance = try {
            implementationClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            throw ExtensionMappingException("Implementation class '${entry.implementation}' could not be instantiated", e)
        }

        logger.debug("Instantiated extension implementation {} for key '{}' of plugin '{}'", implementationClass.name, key, pluginId)
        return ResolvedExtension(key, pluginId, configuration, instance)
    }

    private fun buildConfiguration(
        configurationClass: KClass<out ExtensionConfiguration<*>>,
        implementationClass: Class<*>,
        additionalProperties: Map<String, Any?>,
    ): ExtensionConfiguration<*> {
        val constructor = requireNotNull(configurationClass.primaryConstructor) {
            "${configurationClass.qualifiedName} has no primary constructor"
        }
        val arguments = constructor.parameters.associateWith { parameter ->
            if (parameter.name == "implementation") {
                implementationClass.kotlin
            } else {
                val targetType = (parameter.type.classifier as? KClass<*>)?.java
                    ?: throw ExtensionMappingException("Unsupported parameter type for '${parameter.name}'")
                objectMapper.convertValue(additionalProperties[parameter.name], targetType)
            }
        }
        return constructor.callBy(arguments)
    }
}
