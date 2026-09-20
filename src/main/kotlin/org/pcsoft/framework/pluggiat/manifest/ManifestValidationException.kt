package org.pcsoft.framework.pluggiat.manifest

/**
 * Thrown when a plugin manifest fails schema validation or cannot otherwise be parsed into a
 * [PluginManifest].
 *
 * @property violations human-readable descriptions of the individual schema violations found, empty
 * if the manifest could not be parsed at all
 */
internal class ManifestValidationException(
    message: String,
    val violations: List<String> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)
