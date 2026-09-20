package org.pcsoft.framework.pluggiat.manifest

/**
 * Author information of a plugin, as declared under the `author` key of a plugin manifest.
 *
 * @property name display name of the author or organisation
 * @property mail optional contact mail address
 */
data class Author(
    val name: String,
    val mail: String? = null,
)

/**
 * External links related to a plugin, as declared under the `links` key of a plugin manifest.
 *
 * @property documentation optional URL to the plugin's documentation
 * @property sourceCode optional URL to the plugin's source code repository
 */
data class Links(
    val documentation: String? = null,
    val sourceCode: String? = null,
)

/**
 * Legal information of a plugin, as declared under the `legal` key of a plugin manifest.
 *
 * @property copyright optional free-form copyright notice
 * @property license optional license identifier, ideally an SPDX identifier
 */
data class Legal(
    val copyright: String? = null,
    val license: String? = null,
)

/**
 * A single dependency of a plugin on another plugin, identified by its plugin id.
 *
 * @property id id of the required plugin
 * @property required whether the dependency is mandatory (`true`) or optional (`false`)
 */
data class PluginDependency(
    val id: String,
    val required: Boolean,
)

/**
 * A single entry contributed to an extension point, as declared under `extensions.<key>[]`.
 *
 * @property implementation fully qualified class name of the extension implementation
 */
data class ExtensionEntry(
    val implementation: String,
)

/**
 * The fully parsed and validated content of a plugin manifest (`META-INF/plugin.yml`/`plugin.yaml`).
 *
 * The internal `$version` field of the manifest file is used exclusively for migration purposes
 * while parsing and is intentionally not exposed on this class.
 *
 * @property id unique id of the plugin
 * @property name human-readable display name of the plugin
 * @property version version of the plugin, following the Maven version scheme
 * @property minVersion minimum required version of the host application, following the Maven version scheme
 * @property icon Base64-encoded plugin icon
 * @property description optional free-form description of the plugin
 * @property author optional author information
 * @property links optional external links
 * @property legal optional legal information
 * @property dependencies dependencies of this plugin on other plugins
 * @property extensions extension point entries contributed by this plugin, keyed by extension point key
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val minVersion: String,
    val icon: String,
    val description: String? = null,
    val author: Author? = null,
    val links: Links? = null,
    val legal: Legal? = null,
    val dependencies: List<PluginDependency> = emptyList(),
    val extensions: Map<String, List<ExtensionEntry>> = emptyMap(),
)
