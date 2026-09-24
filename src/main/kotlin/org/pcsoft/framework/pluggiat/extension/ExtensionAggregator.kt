package org.pcsoft.framework.pluggiat.extension

import org.pcsoft.framework.pluggiat.PluginLifecycle
import org.pcsoft.framework.pluggiat.exception.DefaultExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.persistence.NoPersistenceStrategy
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.proxy.ExtensionProxyFactory
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

    /**
     * The plugin is disabled per [PluginPersistenceStrategy]; its extension classes were not
     * resolved/instantiated at all.
     */
    DISABLED,
}

/**
 * A single scanned plugin candidate, as input to [ExtensionAggregator.aggregate].
 *
 * @property pluginId id of the plugin
 * @property path location the plugin was scanned from; passed through unchanged into the result
 * @property manifest the plugin's parsed manifest
 * @property onUnload invoked (in addition to the framework's own enabled/disabled persistence and
 * lifecycle hook calls) whenever a runtime exception resolves to
 * `org.pcsoft.framework.pluggiat.exception.ExceptionHandlingAction.UNLOAD` for this plugin; a
 * caller that has the plugin's `org.pcsoft.framework.pluggiat.classloader.LoadedPlugin` wires this
 * to `LoadedPlugin.close()` so the class loader is actually discarded
 */
data class PluginExtensionCandidate(
    val pluginId: String,
    val path: Path,
    val manifest: PluginManifest,
    val onUnload: () -> Unit = {},
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
 * @property extensionsByKey resolved extensions of all non-rejected, non-disabled plugins, grouped
 * by extension point key; an entry's [ResolvedExtension.instance] is the runtime enforcement proxy
 * (see [ExtensionProxyFactory]) whenever its extension point's host plugin API type is proxy-eligible,
 * the real instance otherwise
 * @property realInstancesByPlugin the same entries' real, unproxied instances grouped by plugin id
 * instead of by key, for internal framework use (e.g. `org.pcsoft.framework.pluggiat.PluginManager.unload`
 * invoking [PluginLifecycle] hooks directly) - never handed out to plugin-facing host code
 */
data class ExtensionAggregationResult(
    val pluginResults: List<PluginExtensionResult>,
    val extensionsByKey: Map<String, List<ResolvedExtension>>,
    internal val realInstancesByPlugin: Map<String, List<Any>> = emptyMap(),
)

/**
 * Aggregates extension entries of multiple plugins per extension point key, applies exclusivity
 * conflict handling and the enabled/disabled status, and enforces the runtime proxy (task 6 of
 * IP-06's implementation plan).
 *
 * @property registry the host's extension point registry
 * @param classResolverFor resolves an entry's `implementation` FQCN to a [Class], per plugin id - a
 * host loading each plugin through its own isolated class loader passes a resolver bound to that
 * plugin's loader (see `org.pcsoft.framework.pluggiat.classloader.PluginExtensionClassResolver`)
 * @property persistenceStrategy source of truth for a plugin's enabled/disabled status (key
 * `"enabled"`, absent/anything but `"false"` means enabled) and disable reason (key
 * `"disabledReason"`)
 * @property exceptionHandlingStrategy resolves the action for a `Throwable` escaping a proxied
 * extension call
 */
class ExtensionAggregator(
    private val registry: ExtensionPointRegistry,
    private val classResolverFor: (pluginId: String) -> ExtensionClassResolver = { DefaultExtensionClassResolver },
    private val persistenceStrategy: PluginPersistenceStrategy = NoPersistenceStrategy(),
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy = DefaultExceptionHandlingStrategy(),
) {
    private val logger = LoggerFactory.getLogger(ExtensionAggregator::class.java)

    /**
     * Resolves the extensions of all enabled [candidates], groups non-exclusive keys into lists,
     * rejects both plugins involved whenever an exclusive key is contributed by more than one
     * plugin, and skips disabled plugins entirely (no class resolution/instantiation at all for
     * them).
     *
     * @throws ExtensionMappingException if any enabled candidate's extension entries cannot be mapped, see [ExtensionDecorator.decorate]
     */
    fun aggregate(candidates: List<PluginExtensionCandidate>): ExtensionAggregationResult {
        val enabledIds = candidates.map { it.pluginId }.filter { isEnabled(it) }.toSet()
        val enabledCandidates = candidates.filter { it.pluginId in enabledIds }
        val realInstances = mutableMapOf<String, MutableList<Any>>()

        val resolvedByPlugin: Map<String, List<ResolvedExtension>> = enabledCandidates.associate { candidate ->
            candidate.pluginId to candidate.manifest.extensions.flatMap { (key, entries) ->
                entries.map { entry -> resolveAndEnforce(candidate, key, entry, realInstances) }
            }
        }

        val contributorsByKey: Map<String, Set<String>> = enabledCandidates
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
                status = when {
                    candidate.pluginId !in enabledIds -> PluginExtensionStatus.DISABLED
                    candidate.pluginId in rejectedPluginIds -> PluginExtensionStatus.REJECTED_EXCLUSIVE_CONFLICT
                    else -> PluginExtensionStatus.LOADED
                },
            )
        }

        val extensionsByKey = resolvedByPlugin
            .filterKeys { it !in rejectedPluginIds }
            .values
            .flatten()
            .groupBy { it.key }

        val realInstancesByPlugin = realInstances.filterKeys { it !in rejectedPluginIds }

        return ExtensionAggregationResult(pluginResults, extensionsByKey, realInstancesByPlugin)
    }

    private fun isEnabled(pluginId: String): Boolean =
        persistenceStrategy.read(pluginId, ENABLED_PERSISTENCE_KEY) != "false"

    private fun resolveAndEnforce(
        candidate: PluginExtensionCandidate,
        key: String,
        entry: org.pcsoft.framework.pluggiat.manifest.ExtensionEntry,
        realInstances: MutableMap<String, MutableList<Any>>,
    ): ResolvedExtension {
        val decorator = ExtensionDecorator(registry, classResolverFor(candidate.pluginId))
        val resolved = decorator.decorate(candidate.pluginId, key, entry)
        val realInstance = resolved.instance
        realInstances.getOrPut(candidate.pluginId) { mutableListOf() }.add(realInstance)
        if (realInstance is PluginLifecycle) {
            logger.debug("Invoking onLoad on extension implementation {} of plugin '{}'", realInstance::class.java.name, candidate.pluginId)
            realInstance.onLoad()
            logger.debug("Invoking onEnable on extension implementation {} of plugin '{}'", realInstance::class.java.name, candidate.pluginId)
            realInstance.onEnable()
        }

        val apiType = registry.registrationFor(key)?.apiType
        val proxied = if (apiType != null) {
            @Suppress("UNCHECKED_CAST")
            ExtensionProxyFactory.create(apiType as Class<Any>, realInstance, exceptionHandlingStrategy) {
                forceDisable(candidate, realInstance)
            }
        } else {
            realInstance
        }
        return resolved.copy(instance = proxied)
    }

    private fun forceDisable(candidate: PluginExtensionCandidate, realInstance: Any) {
        logger.error("Forcibly disabling plugin '{}' due to a runtime UNLOAD action", candidate.pluginId)
        if (realInstance is PluginLifecycle) {
            logger.debug("Invoking onDisable on extension implementation {} of plugin '{}'", realInstance::class.java.name, candidate.pluginId)
            runCatching { realInstance.onDisable() }
            logger.debug("Invoking onUnload on extension implementation {} of plugin '{}'", realInstance::class.java.name, candidate.pluginId)
            runCatching { realInstance.onUnload() }
        }
        persistenceStrategy.write(candidate.pluginId, DISABLED_REASON_PERSISTENCE_KEY, RUNTIME_ERROR_REASON)
        persistenceStrategy.write(candidate.pluginId, ENABLED_PERSISTENCE_KEY, "false")
        logger.info("Plugin '{}' persisted as disabled (reason={})", candidate.pluginId, RUNTIME_ERROR_REASON)
        candidate.onUnload()
    }

    companion object {
        /** [PluginPersistenceStrategy] key holding a plugin's enabled/disabled status (`"true"`/`"false"`). */
        const val ENABLED_PERSISTENCE_KEY: String = "enabled"

        /** [PluginPersistenceStrategy] key holding the reason a plugin was disabled. */
        const val DISABLED_REASON_PERSISTENCE_KEY: String = "disabledReason"

        /** [DISABLED_REASON_PERSISTENCE_KEY] value recorded when [ExceptionHandlingAction.UNLOAD] force-disables a plugin. */
        const val RUNTIME_ERROR_REASON: String = "RUNTIME_ERROR"

        /** [DISABLED_REASON_PERSISTENCE_KEY] value a host records for an explicit user-initiated disable. */
        const val USER_REASON: String = "USER"
    }
}
