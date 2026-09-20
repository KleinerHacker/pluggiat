package org.pcsoft.framework.pluggiat.extension

/**
 * Thrown when a host's extension point registration is invalid, i.e. a registered
 * [ExtensionConfiguration] class is not annotated with [ExtensionPoint] or a key is registered twice.
 */
internal class ExtensionRegistrationException(message: String) : Exception(message)

/**
 * Thrown when a manifest `extensions.<key>[]` entry cannot be mapped onto its registered
 * [ExtensionConfiguration] or its implementation class cannot be resolved/instantiated.
 */
internal class ExtensionMappingException(message: String, cause: Throwable? = null) : Exception(message, cause)
