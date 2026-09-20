package org.pcsoft.framework.pluggiat.extension

import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Outcome of extension resolution for one scanned plugin candidate.
 */
enum class PluginExtensionStatus {
    /** The plugin's extensions were resolved and are active. */
    LOADED,

    /** The plugin contributed to an exclusive extension point key together with at least one other plugin. */
    REJECTED_EXCLUSIVE_CONFLICT,
}

/**
 * A single scanned plugin candidate, as input to [ExtensionAggregator.aggregate].
 *
 * @property pluginId id of the plugin
 * @property path location the plugin was scanned from; passed through unchanged into the result
 * @property manifest the plugin's parsed manifest
 */
data class PluginExtensionCandidate(
    val pluginId: String,
    val path: Path,
    val manifest: PluginManifest,
)

/**
 * Outcome for a single plugin candidate after extension resolution and conflict handling.
 *
 * @property pluginId id of the plugin
 * @property path location the plugin was scanned from, unchanged from [PluginExtensionCandidate.path]
 * @property status resolution outcome for this plugin
 */
data class PluginExtensionResult(
    val pluginId: String,
    val path: Path,
    val status: PluginExtensionStatus,
)

/**
 * Combined result of aggregating extensions across all scanned plugin candidates.
 *
 * @property pluginResults per-plugin outcome (id, path, status)
 * @property extensionsByKey resolved extensions of all non-rejected plugins, grouped by extension point key
 */
data class ExtensionAggregationResult(
    val pluginResults: List<PluginExtensionResult>,
    val extensionsByKey: Map<String, List<ResolvedExtension>>,
)

/**
 * Aggregates extension entries of multiple plugins per extension point key and applies exclusivity
 * conflict handling.
 *
 * @param registry the host's extension point registry
 * @param classResolver resolves an entry's `implementation` FQCN to a [Class]
 */
class ExtensionAggregator(
    private val registry: ExtensionPointRegistry,
    classResolver: ExtensionClassResolver = DefaultExtensionClassResolver,
) {
    private val decorator = ExtensionDecorator(registry, classResolver)
    private val logger = LoggerFactory.getLogger(ExtensionAggregator::class.java)

    /**
     * Resolves the extensions of all [candidates], groups non-exclusive keys into lists, and rejects
     * both plugins involved whenever an exclusive key is contributed by more than one plugin.
     *
     * @throws ExtensionMappingException if any candidate's extension entries cannot be mapped, see [ExtensionDecorator.decorate]
     */
    fun aggregate(candidates: List<PluginExtensionCandidate>): ExtensionAggregationResult {
        val resolvedByPlugin: Map<String, List<ResolvedExtension>> = candidates.associate { candidate ->
            candidate.pluginId to candidate.manifest.extensions.flatMap { (key, entries) ->
                entries.map { entry -> decorator.decorate(candidate.pluginId, key, entry) }
            }
        }

        val contributorsByKey: Map<String, Set<String>> = candidates
            .flatMap { candidate -> candidate.manifest.extensions.keys.map { key -> key to candidate.pluginId } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

        val rejectedPluginIds = mutableSetOf<String>()
        for ((key, contributors) in contributorsByKey) {
            if (registry.isExclusive(key) && contributors.size > 1) {
                logger.warn("Exclusive extension point '{}' is contributed by more than one plugin: {}", key, contributors)
                rejectedPluginIds += contributors
            }
        }

        val pluginResults = candidates.map { candidate ->
            PluginExtensionResult(
                pluginId = candidate.pluginId,
                path = candidate.path,
                status = if (candidate.pluginId in rejectedPluginIds) {
                    PluginExtensionStatus.REJECTED_EXCLUSIVE_CONFLICT
                } else {
                    PluginExtensionStatus.LOADED
                },
            )
        }

        val extensionsByKey = resolvedByPlugin
            .filterKeys { it !in rejectedPluginIds }
            .values
            .flatten()
            .groupBy { it.key }

        return ExtensionAggregationResult(pluginResults, extensionsByKey)
    }
}
