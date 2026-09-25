/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

package org.pcsoft.framework.pluggiat

import org.pcsoft.framework.pluggiat.classloader.CyclicDependencyException
import org.pcsoft.framework.pluggiat.classloader.DependencyGraph
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginDependencyStrategy
import org.pcsoft.framework.pluggiat.classloader.PluginExtensionClassResolver
import org.pcsoft.framework.pluggiat.classloader.PluginLoadResult
import org.pcsoft.framework.pluggiat.classloader.PluginLoader
import org.pcsoft.framework.pluggiat.classloader.SdkWhitelistEntry
import org.pcsoft.framework.pluggiat.classloader.UnrestrictedPluginDependencyStrategy
import org.pcsoft.framework.pluggiat.exception.DefaultExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregationResult
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregator
import org.pcsoft.framework.pluggiat.extension.ExtensionConfiguration
import org.pcsoft.framework.pluggiat.extension.ExtensionPointRegistry
import org.pcsoft.framework.pluggiat.extension.PluginExtensionCandidate
import org.pcsoft.framework.pluggiat.extension.ResolvedExtension
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.orchestration.IdCollisionResolver
import org.pcsoft.framework.pluggiat.orchestration.MinVersionChecker
import org.pcsoft.framework.pluggiat.persistence.NoPersistenceStrategy
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.PluginScanStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginScanner
import org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy
import org.pcsoft.framework.pluggiat.security.PersistableSecurityStrategy
import org.pcsoft.framework.pluggiat.security.PluginSecurity
import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * Builder for a single [PluginLocation], used by [PluginManagerConfiguration.location].
 */
class PluginLocationBuilder {
    /** See [PluginLocation.path]. */
    lateinit var path: Path

    /** See [PluginLocation.type]. */
    lateinit var type: PluginLocationType

    /** See [PluginLocation.scanStrategy]. */
    var scanStrategy: PluginScanStrategy = ZipJarScanStrategy()

    /** See [PluginLocation.dependencyStrategyOverride]. */
    var dependencyStrategyOverride: PluginDependencyStrategy? = null

    private var securityOverrideChain: List<PluginSecurityStrategy> = emptyList()

    /**
     * Configures this location's `securityOverride` fallback chain via [SecurityChainBuilder.addStrategy].
     */
    fun securityOverride(block: SecurityChainBuilder.() -> Unit) {
        securityOverrideChain = SecurityChainBuilder().apply(block).build()
    }

    internal fun build(): PluginLocation = PluginLocation(
        path = path,
        type = type,
        scanStrategy = scanStrategy,
        securityOverride = securityOverrideChain,
        dependencyStrategyOverride = dependencyStrategyOverride,
    )
}

/**
 * Builder for an ordered [PluginSecurityStrategy] fallback chain, shared by
 * [PluginLocationBuilder.securityOverride] and [DefaultSecurityChainBuilder].
 */
open class SecurityChainBuilder {
    private val chain: MutableList<PluginSecurityStrategy> = mutableListOf()

    /**
     * Appends [strategy] to the end of this chain.
     */
    fun addStrategy(strategy: PluginSecurityStrategy) {
        chain += strategy
    }

    internal fun build(): List<PluginSecurityStrategy> = chain.toList()
}

/**
 * Builder for a [PluginManagerConfiguration.defaultSecurityChain] entry: a [SecurityChainBuilder]
 * additionally bound to the [PluginLocationType] it is the default for.
 */
class DefaultSecurityChainBuilder : SecurityChainBuilder() {
    /** The [PluginLocationType] this chain becomes the default for. */
    lateinit var type: PluginLocationType
}

/**
 * Builder for a single [SdkWhitelistEntry], used by [PluginManagerConfiguration.sdkWhitelistEntry].
 */
class SdkWhitelistEntryBuilder {
    /** See [SdkWhitelistEntry.packageName]. */
    lateinit var packageName: String

    /** See [SdkWhitelistEntry.recursive]. */
    var recursive: Boolean = true

    internal fun build(): SdkWhitelistEntry = SdkWhitelistEntry(packageName, recursive)
}

/**
 * Host-wide configuration for a [PluginManager], filled in through the [pluginManager] builder
 * DSL.
 *
 * @property pluginLocations plugin locations to scan, see [PluginScanner.scan]
 * @property defaultSecurityChains default security fallback chain per [PluginLocationType], used
 * by locations without their own [PluginLocation.securityOverride]
 * @property dependencyStrategy global [PluginDependencyStrategy], used by locations without their
 * own `dependencyStrategyOverride`
 * @property persistenceStrategy the single [PluginPersistenceStrategy] instance for the whole
 * framework; defaults to [NoPersistenceStrategy], which is not recommended for production use
 * @property exceptionHandlingStrategy the single, host-wide [ExceptionHandlingStrategy]
 * @property sdkWhitelist packages of the host's own SDK exposed to plugins, see [SdkWhitelistEntry]
 * @property hostVersion the host application's own version, following the Maven version scheme,
 * matched against a plugin manifest's `minVersion`
 * @property extensionPointClasses the host's [ExtensionConfiguration] classes, see [extensionPoint]
 */
class PluginManagerConfiguration {
    val pluginLocations: MutableList<PluginLocation> = mutableListOf()
    val defaultSecurityChains: MutableMap<PluginLocationType, List<PluginSecurityStrategy>> = mutableMapOf()
    var dependencyStrategy: PluginDependencyStrategy = UnrestrictedPluginDependencyStrategy()
    var persistenceStrategy: PluginPersistenceStrategy = NoPersistenceStrategy()
    var exceptionHandlingStrategy: ExceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    val sdkWhitelist: MutableList<SdkWhitelistEntry> = mutableListOf()
    var hostVersion: String? = null
    val extensionPointClasses: MutableList<KClass<out ExtensionConfiguration<*>>> = mutableListOf()

    /**
     * Builds a [PluginLocation] via [block] and adds it to [pluginLocations].
     */
    fun location(block: PluginLocationBuilder.() -> Unit) {
        pluginLocations += PluginLocationBuilder().apply(block).build()
    }

    /**
     * Builds the default security fallback chain for one [PluginLocationType] via [block] and sets
     * it in [defaultSecurityChains].
     */
    fun defaultSecurityChain(block: DefaultSecurityChainBuilder.() -> Unit) {
        val builder = DefaultSecurityChainBuilder().apply(block)
        defaultSecurityChains[builder.type] = builder.build()
    }

    /**
     * Builds an [SdkWhitelistEntry] via [block] and adds it to [sdkWhitelist].
     */
    fun sdkWhitelistEntry(block: SdkWhitelistEntryBuilder.() -> Unit) {
        sdkWhitelist += SdkWhitelistEntryBuilder().apply(block).build()
    }

    /**
     * Registers [configurationClass] as one of the host's extension points, see [ExtensionPointRegistry].
     */
    fun extensionPoint(configurationClass: KClass<out ExtensionConfiguration<*>>) {
        extensionPointClasses += configurationClass
    }
}

/**
 * Central, host-wide entry point of the plugin framework.
 *
 * Built via the [pluginManager] Kotlin builder DSL from a [PluginManagerConfiguration]. Bundles the framework's
 * host-wide configuration (plugin locations, default security chains, dependency strategy,
 * persistence strategy, exception handling strategy, SDK whitelist, host version, extension points)
 * in one place and exposes pre-wired [scanner], [security], [loader] and [registry] instances built
 * from it.
 *
 * Stateful by design: [scan] (and [reload]/[unload]/[forceLoad] afterward) mutate [scanResults],
 * [loadedPlugins] and [extensionsByKey] in place rather than returning a combined result object, so
 * a host always finds the current state on the [PluginManager] instance itself. All public methods
 * are internally synchronized and safe to call from any thread.
 *
 * Typical flow: 1. [scan]; 2. the host inspects [scanResults] for anything other than
 * [PluginScanStatus.LOADED] and decides what to do about it (e.g. asking a user); 3. optionally
 * [forceLoad] a candidate the host decides to load despite a failed check; 4. the host accesses
 * loaded extension implementations via [getExtensions]/[getFirstExtension].
 *
 * @property config the configuration this instance was built from
 */
class PluginManager(val config: PluginManagerConfiguration) {
    private val logger = LoggerFactory.getLogger(PluginManager::class.java)
    private val lock = ReentrantLock()

    /**
     * A [PluginSecurity] instance, also used to pre-wire [scanner]. Built with
     * [PluginManagerConfiguration.persistenceStrategy] so [PluginSecurity.SECURITY_EXCEPTION_KEY] is
     * honored.
     */
    val security: PluginSecurity = PluginSecurity(config.persistenceStrategy)

    /**
     * A [PluginScanner] pre-wired with [PluginManagerConfiguration.defaultSecurityChains].
     */
    val scanner: PluginScanner = PluginScanner(config.defaultSecurityChains, security)

    /**
     * A [PluginLoader] pre-wired with [PluginManagerConfiguration.sdkWhitelist].
     */
    val loader: PluginLoader = PluginLoader(sdkWhitelist = config.sdkWhitelist)

    /**
     * The host's extension point registry, built once from [PluginManagerConfiguration.extensionPointClasses].
     */
    val registry: ExtensionPointRegistry = ExtensionPointRegistry(config.extensionPointClasses)

    /**
     * The current state of every plugin candidate found by the last [scan], including
     * orchestration-level outcomes (id collision, `minVersion`, load failure) on top of the plain
     * scan/security status.
     */
    var scanResults: List<PluginScanResult> = emptyList()
        private set

    /**
     * Currently loaded plugins, keyed by plugin id.
     */
    var loadedPlugins: Map<String, LoadedPlugin> = emptyMap()
        private set

    /**
     * Currently active, enabled extensions of all loaded plugins, grouped by extension point key.
     * Use [getExtensions]/[getFirstExtension] for typed access.
     */
    var extensionsByKey: Map<String, List<ResolvedExtension>> = emptyMap()
        private set

    private var lastAggregation: ExtensionAggregationResult = ExtensionAggregationResult(emptyList(), emptyMap())

    /**
     * Scans all [PluginManagerConfiguration.pluginLocations], resolves id collisions, checks
     * `minVersion`, loads every remaining candidate (in dependency order) and activates its
     * extensions - updating [scanResults], [loadedPlugins] and [extensionsByKey] in place.
     *
     * Any plugin loaded by a previous [scan] that is no longer present in the new result is closed.
     */
    fun scan() {
        lock.withLock {
            var results = scanner.scan(config.pluginLocations)
            results = IdCollisionResolver().resolve(results)
            results = results.map { result ->
                if (result.status == PluginScanStatus.LOADED) MinVersionChecker.check(result, config.hostVersion) else result
            }

            val loadable = results.filter { it.status == PluginScanStatus.LOADED }
            val resultByManifest = loadable.associateBy { it.manifest!! }
            val finalResults = results.toMutableList()
            val newLoaded = mutableMapOf<String, LoadedPlugin>()

            try {
                val ordered = DependencyGraph.topologicalOrder(
                    manifests = loadable.map { it.manifest!! },
                    locationOf = { manifest -> resultByManifest.getValue(manifest).path },
                    dependencyStrategyOf = { path -> dependencyStrategyFor(resultByManifest.values.first { it.path == path }.location) },
                )
                for (manifest in ordered) {
                    val scanResult = resultByManifest.getValue(manifest)
                    val dependencies = visibleDependencies(
                        scanResult.path, scanResult.location, manifest,
                        pathOf = { id -> resultByManifest.values.firstOrNull { it.manifest?.id == id }?.path },
                        available = newLoaded,
                    )
                    val pinnedContent = requireNotNull(scanResult.pinnedContent) {
                        "No pinned content for plugin '${manifest.id}' with status LOADED"
                    }
                    when (val loadResult = loader.load(pinnedContent, manifest, dependencies)) {
                        is PluginLoadResult.Loaded -> newLoaded[manifest.id] = loadResult.plugin
                        is PluginLoadResult.Invalid -> markLoadFailed(finalResults, scanResult, loadResult.reason)
                    }
                }
            } catch (e: CyclicDependencyException) {
                logger.error("Cyclic plugin dependency detected, no candidate of this scan was loaded: {}", e.cycle)
                for (scanResult in loadable) {
                    markLoadFailed(finalResults, scanResult, "Cyclic plugin dependency: ${e.cycle.joinToString(" -> ")}")
                }
            }

            for ((pluginId, plugin) in loadedPlugins) {
                if (pluginId !in newLoaded) {
                    plugin.close()
                }
            }

            scanResults = finalResults
            loadedPlugins = newLoaded
            reaggregateExtensions()
        }
    }

    private fun dependencyStrategyFor(location: PluginLocation): PluginDependencyStrategy =
        location.dependencyStrategyOverride ?: config.dependencyStrategy

    /**
     * Filters [manifest]'s declared dependencies down to those actually present in [available] AND
     * visible from [fromPath] per [fromLocation]'s effective [PluginDependencyStrategy] (see
     * [dependencyStrategyFor]) - an invisible or unmet dependency is treated exactly like a missing
     * one, consistent with [DependencyGraph.topologicalOrder].
     */
    private fun visibleDependencies(
        fromPath: Path,
        fromLocation: PluginLocation,
        manifest: PluginManifest,
        pathOf: (pluginId: String) -> Path?,
        available: Map<String, LoadedPlugin>,
    ): Map<String, LoadedPlugin> {
        val strategy = dependencyStrategyFor(fromLocation)
        return manifest.dependencies.mapNotNull { dependency ->
            val loaded = available[dependency.id] ?: return@mapNotNull null
            val dependencyPath = pathOf(dependency.id) ?: return@mapNotNull null
            (dependency.id to loaded).takeIf { strategy.isVisible(fromPath, dependencyPath) }
        }.toMap()
    }

    private fun markLoadFailed(results: MutableList<PluginScanResult>, scanResult: PluginScanResult, reason: String) {
        val index = results.indexOf(scanResult)
        results[index] = scanResult.copy(status = PluginScanStatus.LOAD_FAILED, errorMessage = reason)
    }

    /**
     * Re-checks security for the plugin candidate at [path] within [location] and, only on success,
     * reloads it via [loader] and persists it as enabled again via
     * [PluginManagerConfiguration.persistenceStrategy].
     *
     * A failed re-check keeps the plugin disabled (its disable reason is updated to reflect the
     * failed re-check) and never falls back to a force-load - a host that wants to force-load
     * despite the failure calls [forceLoad] instead.
     */
    fun reactivate(
        location: PluginLocation,
        path: Path,
        manifest: PluginManifest,
        dependencies: Map<String, LoadedPlugin> = emptyMap(),
    ): PluginLoadResult {
        val (check, pinnedContent) = security.reevaluateAndPin(location, path, config.defaultSecurityChains)
        if (check is PluginSecurityCheckResult.Failure || pinnedContent == null) {
            val reason = (check as? PluginSecurityCheckResult.Failure)?.reason ?: "no pinned content"
            logger.warn("Security re-check failed for plugin '{}' on reactivation: {}", manifest.id, reason)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY, SECURITY_RECHECK_FAILED_REASON)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "false")
            return PluginLoadResult.Invalid(manifest.id, "Security re-check failed on reactivation: $reason")
        }

        val result = loader.load(pinnedContent, manifest, dependencies)
        if (result is PluginLoadResult.Loaded) {
            logger.info("Plugin '{}' passed the security re-check and was reloaded, persisted as enabled again", manifest.id)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "true")
        }
        return result
    }

    /**
     * Regular, secure reload of a single plugin: re-checks security for its current [scanResults]
     * entry via [reactivate] and, only on success, replaces its entry in [loadedPlugins] and
     * refreshes [extensionsByKey]. A failed re-check leaves [loadedPlugins]/[extensionsByKey]
     * untouched.
     *
     * @throws NoSuchElementException if no [scanResults] entry with [pluginId] is known
     */
    fun reload(pluginId: String): PluginLoadResult {
        lock.withLock {
            val scanResult = scanResults.first { it.manifest?.id == pluginId }
            val manifest = requireNotNull(scanResult.manifest) { "No manifest known for plugin '$pluginId'" }
            val dependencies = visibleDependencies(
                scanResult.path, scanResult.location, manifest,
                pathOf = { id -> scanResults.firstOrNull { it.manifest?.id == id }?.path },
                available = loadedPlugins,
            )

            val result = reactivate(scanResult.location, scanResult.path, manifest, dependencies)
            if (result is PluginLoadResult.Loaded) {
                loadedPlugins[pluginId]?.close()
                loadedPlugins = loadedPlugins + (pluginId to result.plugin)
                scanResults = scanResults.map { if (it === scanResult) it.copy(status = PluginScanStatus.LOADED, errorMessage = null) else it }
                reaggregateExtensions()
            }
            return result
        }
    }

    /**
     * Deliberate, host-initiated deactivation of a loaded plugin: the counterpart to [reload].
     * Invokes [PluginLifecycle.onDisable]/[PluginLifecycle.onUnload] on the plugin's real (unproxied)
     * extension instances, persists it as disabled (reason [ExtensionAggregator.USER_REASON]), closes
     * its class loader and removes it from [loadedPlugins]/[extensionsByKey].
     *
     * A no-op if [pluginId] is not currently in [loadedPlugins].
     */
    fun unload(pluginId: String) {
        lock.withLock {
            val plugin = loadedPlugins[pluginId] ?: return
            lastAggregation.realInstancesByPlugin[pluginId].orEmpty().forEach { instance ->
                if (instance is PluginLifecycle) {
                    logger.debug("Invoking onDisable on extension implementation {} of plugin '{}'", instance::class.java.name, pluginId)
                    runCatching { instance.onDisable() }
                    logger.debug("Invoking onUnload on extension implementation {} of plugin '{}'", instance::class.java.name, pluginId)
                    runCatching { instance.onUnload() }
                }
            }
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY, ExtensionAggregator.USER_REASON)
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "false")
            plugin.close()
            loadedPlugins = loadedPlugins - pluginId
            logger.info("Plugin '{}' unloaded and persisted as disabled (reason={})", pluginId, ExtensionAggregator.USER_REASON)
            reaggregateExtensions()
        }
    }

    /**
     * Loads the plugin candidate for [pluginId] from [scanResults] unconditionally via [loader],
     * bypassing its current [PluginScanResult.status] entirely - the caller (typically after asking
     * the host's user for confirmation) is solely responsible for deciding this is warranted. Logs a
     * WARN with the original status/reason and the fact that this was a force-load. The
     * [scanResults] entry itself is left unchanged; only [loadedPlugins]/[extensionsByKey] reflect
     * the plugin as loaded afterward.
     *
     * @param persistException if `true`, additionally persists a permanent
     * [PluginSecurity.SECURITY_EXCEPTION_KEY] for [pluginId], so every future security check for it
     * is skipped - use this when the underlying strategy has no dedicated [PersistableSecurityStrategy.persist]
     * (e.g. a signature strategy); prefer [write] for a [PersistableSecurityStrategy] like the
     * checksum strategy instead
     * @throws NoSuchElementException if no [scanResults] entry with [pluginId] is known
     */
    fun forceLoad(pluginId: String, persistException: Boolean = false): PluginLoadResult {
        lock.withLock {
            val scanResult = scanResults.first { it.manifest?.id == pluginId }
            val manifest = requireNotNull(scanResult.manifest) { "No manifest known for plugin '$pluginId'" }
            val dependencies = visibleDependencies(
                scanResult.path, scanResult.location, manifest,
                pathOf = { id -> scanResults.firstOrNull { it.manifest?.id == id }?.path },
                available = loadedPlugins,
            )

            logger.warn(
                "Force-loading plugin '{}' despite status {} ({})", pluginId, scanResult.status, scanResult.errorMessage,
            )
            val result = loader.load(scanResult.path, manifest, dependencies)
            if (result is PluginLoadResult.Loaded) {
                loadedPlugins = loadedPlugins + (pluginId to result.plugin)
                reaggregateExtensions()
            }
            if (persistException) {
                config.persistenceStrategy.write(pluginId, PluginSecurity.SECURITY_EXCEPTION_KEY, "true")
                logger.warn("Persistent security exception recorded for plugin '{}' - every future security check for it will be skipped", pluginId)
            }
            return result
        }
    }

    /**
     * Persists the accepted state of a [PersistableSecurityStrategy] of type [T] configured for
     * [pluginId]'s current [scanResults] entry, so a future [PluginSecurity.evaluate]/[reload]
     * succeeds through the regular chain instead of requiring another [forceLoad].
     *
     * @throws NoSuchElementException if no [scanResults] entry with [pluginId] is known
     * @throws IllegalStateException if [pluginId]'s effective security chain contains no strategy of type [T]
     */
    inline fun <reified T : PersistableSecurityStrategy> write(pluginId: String) {
        val result = scanResults.first { it.manifest?.id == pluginId }
        val strategy = effectiveChainFor(result).filterIsInstance<T>().firstOrNull()
            ?: error("No configured security strategy of type ${T::class.simpleName} for plugin '$pluginId'")
        strategy.persist(pluginId, result)
    }

    /**
     * Typed access to the currently active extensions contributed under [key] (see [extensionsByKey]).
     * Entries whose real instance is not assignable to [T] are silently skipped.
     */
    inline fun <reified T> getExtensions(key: String): List<T> =
        extensionsByKey[key].orEmpty().mapNotNull { it.instance as? T }

    /**
     * Typed access to the first currently active extension contributed under [key] - the natural
     * accessor for an exclusive extension point (see [extensionsByKey]).
     */
    inline fun <reified T> getFirstExtension(key: String): T? =
        extensionsByKey[key]?.firstOrNull()?.instance as? T

    @PublishedApi
    internal fun effectiveChainFor(result: PluginScanResult): List<PluginSecurityStrategy> =
        PluginSecurity.effectiveChain(result.location, config.defaultSecurityChains)

    private fun reaggregateExtensions() {
        val aggregator = ExtensionAggregator(
            registry = registry,
            classResolverFor = { pluginId -> PluginExtensionClassResolver(loadedPlugins.getValue(pluginId).classLoader) },
            persistenceStrategy = config.persistenceStrategy,
            exceptionHandlingStrategy = config.exceptionHandlingStrategy,
        )
        val candidates = loadedPlugins.values.map { plugin ->
            val path = scanResults.first { it.manifest?.id == plugin.pluginId }.path
            PluginExtensionCandidate(
                pluginId = plugin.pluginId,
                path = path,
                manifest = plugin.manifest,
                onUnload = { closeAfterRuntimeUnload(plugin.pluginId) },
            )
        }
        lastAggregation = aggregator.aggregate(candidates)
        extensionsByKey = lastAggregation.extensionsByKey
    }

    private fun closeAfterRuntimeUnload(pluginId: String) {
        lock.withLock {
            loadedPlugins[pluginId]?.close()
            loadedPlugins = loadedPlugins - pluginId
        }
    }

    companion object {
        /** [ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY] value recorded by a failed [reactivate] re-check. */
        const val SECURITY_RECHECK_FAILED_REASON: String = "SECURITY_RECHECK_FAILED"
    }
}

/**
 * Builds a [PluginManager] from a [PluginManagerConfiguration] filled in by [applyConfig].
 */
fun pluginManager(applyConfig: PluginManagerConfiguration.() -> Unit): PluginManager {
    val config = PluginManagerConfiguration().apply(applyConfig)
    return PluginManager(config)
}
