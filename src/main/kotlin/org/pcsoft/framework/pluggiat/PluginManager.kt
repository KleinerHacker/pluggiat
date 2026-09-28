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
import org.pcsoft.framework.pluggiat.sandbox.PluginSandbox
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxViolationException
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
import java.util.concurrent.ConcurrentHashMap
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

    /** See [PluginLocation.sandboxOverride]. */
    var sandboxOverride: PluginSandboxPolicy? = null

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
        sandboxOverride = sandboxOverride,
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
 * Builder for a [PluginManagerConfiguration.defaultSandboxPolicy] entry: a [PluginSandboxPolicy]
 * bound to the [PluginLocationType] it becomes the default for.
 */
class DefaultSandboxPolicyBuilder {
    /** The [PluginLocationType] [policy] becomes the default for. */
    lateinit var type: PluginLocationType

    /** The [PluginSandboxPolicy] to use as the default for [type]. */
    var policy: PluginSandboxPolicy = PluginSandboxPolicy.UNRESTRICTED
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
 * @property sandboxPolicies default sandbox policy per [PluginLocationType], used by locations
 * without their own [PluginLocation.sandboxOverride]; see [PluginSandbox.effectivePolicy]
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
    val sandboxPolicies: MutableMap<PluginLocationType, PluginSandboxPolicy> = mutableMapOf()
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
     * Builds the default sandbox policy for one [PluginLocationType] via [block] and sets it in
     * [sandboxPolicies].
     */
    fun defaultSandboxPolicy(block: DefaultSandboxPolicyBuilder.() -> Unit) {
        val builder = DefaultSandboxPolicyBuilder().apply(block)
        sandboxPolicies[builder.type] = builder.policy
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
 * in one place and exposes pre-wired [scanner], [security], [sandbox], [loader] and [registry]
 * instances built from it.
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
 * Every [scan], [reload], [unload] and [forceLoad] re-aggregates the extensions of *all* loaded
 * plugins: the instances of the previous aggregation first receive [PluginLifecycle.onDisable] and
 * [PluginLifecycle.onUnload], then every active plugin is instantiated again and receives
 * [PluginLifecycle.onLoad] and [PluginLifecycle.onEnable].
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
     * The single, host-wide [PluginSandbox] instance - the only sandbox-related object this class
     * (or a host) ever addresses directly, analogous to [security] for the pre-load security chain.
     * As of IP-01 it is backed by a no-op enforcer; see [PluginSandbox] for details.
     */
    val sandbox: PluginSandbox = PluginSandbox()

    init {
        sandbox.violationListener = ::handleSandboxViolation
    }

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
     * Ids of plugins whose instances of [lastAggregation] were already finished by another path (a
     * regular [unload], a runtime `UNLOAD` or a forced sandbox unload), so [tearDownPreviousInstances]
     * must not invoke their lifecycle hooks a second time - or at all, for a plugin that just attacked
     * the sandbox.
     */
    private val finishedInstancePluginIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Per-plugin-id cumulative count of category-less (timeout) sandbox violations, only ever
     * read/written from within [handleSandboxViolation]. Successful calls, [unload] and [reload] do not
     * reset it: the counter is cleared only by a category-attributed violation or once
     * [MAX_TIMEOUT_VIOLATIONS] is reached, at which point the plugin is force-unloaded exactly like a
     * category-attributed violation - see [handleSandboxViolation].
     */
    private val timeoutViolationCounts = ConcurrentHashMap<String, Int>()

    /**
     * Scans all [PluginManagerConfiguration.pluginLocations], resolves id collisions, checks
     * `minVersion`, loads every remaining candidate (in dependency order) and activates its
     * extensions - updating [scanResults], [loadedPlugins] and [extensionsByKey] in place.
     *
     * Any plugin loaded by a previous [scan] that is no longer present in the new result is closed.
     */
    fun scan() {
        lock.withLock {
            logger.trace("Starting scan of {} plugin location(s), each pre-checked via its effective PluginSecurity chain", config.pluginLocations.size)
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
                        is PluginLoadResult.Loaded -> {
                            newLoaded[manifest.id] = loadResult.plugin
                            sandbox.activate(loadResult.plugin, effectiveSandboxPolicyFor(scanResult.location))
                        }
                        is PluginLoadResult.Invalid -> markLoadFailed(finalResults, scanResult, loadResult.reason)
                    }
                }
            } catch (e: CyclicDependencyException) {
                logger.error("Cyclic plugin dependency detected, no candidate of this scan was loaded: {}", e.cycle)
                for (scanResult in loadable) {
                    markLoadFailed(finalResults, scanResult, "Cyclic plugin dependency: ${e.cycle.joinToString(" -> ")}")
                }
            }

            // The previous instances get their hooks while their class loaders are still open.
            tearDownPreviousInstances()
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
     * The effective [PluginSandboxPolicy] for [location], see [PluginSandbox.effectivePolicy].
     */
    private fun effectiveSandboxPolicyFor(location: PluginLocation): PluginSandboxPolicy =
        PluginSandbox.effectivePolicy(location, config.sandboxPolicies)

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
     * Refuses [operation] for a plugin whose [scanResults] entry is [PluginScanStatus.POTENTIAL_ATTACK]:
     * unlike every other non-`LOADED` status, a plugin marked by a runtime sandbox violation has no host
     * override, so neither [reload], [reactivate] nor [forceLoad] may bring it back.
     *
     * @throws IllegalStateException if [pluginId] is marked [PluginScanStatus.POTENTIAL_ATTACK]
     */
    private fun requireNotMarkedAsAttack(pluginId: String, operation: String) {
        // SECURITY: checked before anything is re-read, re-checked or persisted, so a plugin that attacked
        // SECURITY: the sandbox cannot be re-enabled through any entry point, however its security chain votes.
        check(scanResults.none { it.manifest?.id == pluginId && it.status == PluginScanStatus.POTENTIAL_ATTACK }) {
            "Plugin '$pluginId' is marked POTENTIAL_ATTACK (runtime sandbox violation) and cannot be $operation"
        }
    }

    /**
     * Re-checks security for the plugin candidate at [path] within [location] and, only on success,
     * reloads it via [loader] and persists it as enabled again via
     * [PluginManagerConfiguration.persistenceStrategy].
     *
     * A failed re-check keeps the plugin disabled (its disable reason is updated to reflect the
     * failed re-check) and never falls back to a force-load - a host that wants to force-load
     * despite the failure calls [forceLoad] instead.
     *
     * @throws IllegalStateException if [manifest]'s plugin is marked [PluginScanStatus.POTENTIAL_ATTACK]
     * (runtime sandbox violation); such a plugin can never be reactivated
     */
    fun reactivate(
        location: PluginLocation,
        path: Path,
        manifest: PluginManifest,
        dependencies: Map<String, LoadedPlugin> = emptyMap(),
    ): PluginLoadResult {
        requireNotMarkedAsAttack(manifest.id, "reactivated")
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
            sandbox.activate(result.plugin, effectiveSandboxPolicyFor(location))
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
     * @throws IllegalStateException if [pluginId]'s [PluginScanResult.status] is [PluginScanStatus.POTENTIAL_ATTACK] -
     * a plugin marked by a runtime sandbox violation can never be reloaded
     */
    fun reload(pluginId: String): PluginLoadResult {
        lock.withLock {
            requireNotMarkedAsAttack(pluginId, "reloaded")
            val scanResult = scanResults.first { it.manifest?.id == pluginId }
            val manifest = requireNotNull(scanResult.manifest) { "No manifest known for plugin '$pluginId'" }
            val dependencies = visibleDependencies(
                scanResult.path, scanResult.location, manifest,
                pathOf = { id -> scanResults.firstOrNull { it.manifest?.id == id }?.path },
                available = loadedPlugins,
            )

            val result = reactivate(scanResult.location, scanResult.path, manifest, dependencies)
            if (result is PluginLoadResult.Loaded) {
                // The previous instances get their hooks before the sandbox registration of the old loader is revoked.
                tearDownPreviousInstances()
                sandbox.deactivate(pluginId, loadedPlugins[pluginId]?.classLoader)
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
     * extension instances - under [sandbox]'s governance for this plugin's effective sandbox policy -
     * persists it as disabled (reason [ExtensionAggregator.USER_REASON]), closes its class loader,
     * releases the plugin's [sandbox] state and removes it from [loadedPlugins]/[extensionsByKey].
     *
     * A plugin whose `onDisable`/`onUnload` exceeds its sandbox timeout does *not* prevent any of
     * this: the timeout is logged and the deactivation still fully completes (persistence, `close`,
     * [PluginSandbox.deactivate], removal, reaggregation) - a plugin can therefore never block its own
     * unload by hanging in a lifecycle hook.
     *
     * A no-op if [pluginId] is not currently in [loadedPlugins].
     */
    fun unload(pluginId: String) {
        lock.withLock {
            val plugin = loadedPlugins[pluginId] ?: return
            val location = scanResults.firstOrNull { it.manifest?.id == pluginId }?.location
            val policy = location?.let { effectiveSandboxPolicyFor(it) } ?: PluginSandboxPolicy.UNRESTRICTED
            runLifecycleTeardown(pluginId, policy, lastAggregation.realInstancesByPlugin[pluginId].orEmpty())
            finishedInstancePluginIds += pluginId
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY, ExtensionAggregator.USER_REASON)
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "false")
            plugin.close()
            sandbox.deactivate(pluginId, plugin.classLoader)
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
     * @throws IllegalStateException if [pluginId]'s [PluginScanResult.status] is [PluginScanStatus.POTENTIAL_ATTACK] -
     * unlike every other status, this one can never be force-loaded (see [handleSandboxViolation])
     */
    fun forceLoad(pluginId: String, persistException: Boolean = false): PluginLoadResult {
        lock.withLock {
            val scanResult = scanResults.first { it.manifest?.id == pluginId }
            requireNotMarkedAsAttack(pluginId, "force-loaded")
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
                sandbox.activate(result.plugin, effectiveSandboxPolicyFor(scanResult.location))
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
        // The pinned bytes, not the path: what a host accepts here must be what the security chain
        // actually checked (see PersistableSecurityStrategy.persist).
        strategy.persist(pluginId, result, result.pinnedContent)
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

    /**
     * Invokes [PluginLifecycle.onDisable] and then [PluginLifecycle.onUnload] on every [instances] entry
     * that implements [PluginLifecycle], under [sandbox]'s governance for [pluginId].
     *
     * A failing hook is swallowed per instance, and a [SandboxTimeoutException] is logged and swallowed:
     * a plugin can never block the operation that tears it down by hanging in a hook.
     */
    private fun runLifecycleTeardown(pluginId: String, policy: PluginSandboxPolicy, instances: List<Any>) {
        try {
            sandbox.runGoverned(pluginId, policy) {
                instances.forEach { instance ->
                    if (instance is PluginLifecycle) {
                        logger.debug("Invoking onDisable on extension implementation {} of plugin '{}'", instance::class.java.name, pluginId)
                        runCatching { instance.onDisable() }
                        logger.debug("Invoking onUnload on extension implementation {} of plugin '{}'", instance::class.java.name, pluginId)
                        runCatching { instance.onUnload() }
                    }
                }
            }
        } catch (e: SandboxTimeoutException) {
            logger.error(
                "Plugin '{}' timed out invoking onDisable/onUnload; continuing anyway - its " +
                    "abandoned worker thread may still be running in the background",
                pluginId, e,
            )
        }
    }

    /**
     * Ends the lifecycle of every instance of the previous aggregation ([lastAggregation]) that no other
     * path has finished yet (see [finishedInstancePluginIds]) and forgets that aggregation together with
     * [extensionsByKey], so the following [reaggregateExtensions] starts from a clean state and no
     * instance is instantiated twice without having been unloaded.
     *
     * Must run while the class loaders and sandbox registrations of the affected plugins are still
     * intact. Idempotent: a second call finds nothing left to tear down.
     */
    private fun tearDownPreviousInstances() {
        val previous = lastAggregation.realInstancesByPlugin
        lastAggregation = ExtensionAggregationResult(emptyList(), emptyMap())
        extensionsByKey = emptyMap()
        for ((pluginId, instances) in previous) {
            if (pluginId in finishedInstancePluginIds) continue
            val location = scanResults.firstOrNull { it.manifest?.id == pluginId }?.location
            val policy = location?.let { effectiveSandboxPolicyFor(it) } ?: PluginSandboxPolicy.UNRESTRICTED
            runLifecycleTeardown(pluginId, policy, instances)
        }
        finishedInstancePluginIds.clear()
    }

    private fun reaggregateExtensions() {
        tearDownPreviousInstances()
        val aggregator = ExtensionAggregator(
            registry = registry,
            classResolverFor = { pluginId -> PluginExtensionClassResolver(loadedPlugins.getValue(pluginId).classLoader) },
            persistenceStrategy = config.persistenceStrategy,
            exceptionHandlingStrategy = config.exceptionHandlingStrategy,
            sandbox = sandbox,
            policyResolver = { pluginId ->
                scanResults.firstOrNull { it.manifest?.id == pluginId }
                    ?.let { effectiveSandboxPolicyFor(it.location) }
                    ?: PluginSandboxPolicy.UNRESTRICTED
            },
        )
        val candidates = loadedPlugins.values.map { plugin ->
            val result = scanResults.first { it.manifest?.id == plugin.pluginId }
            PluginExtensionCandidate(
                pluginId = plugin.pluginId,
                path = result.path,
                manifest = plugin.manifest,
                onUnload = { closeAfterRuntimeUnload(plugin.pluginId) },
                // Passed on so a process-isolated plugin's subprocess is started from the very bytes the
                // security chain accepted, not from its path (see ProcessIsolationStrategy).
                pinnedContent = result.pinnedContent,
            )
        }
        lastAggregation = aggregator.aggregate(candidates)
        extensionsByKey = lastAggregation.extensionsByKey
    }

    private fun closeAfterRuntimeUnload(pluginId: String) {
        lock.withLock {
            val plugin = loadedPlugins[pluginId]
            finishedInstancePluginIds += pluginId
            plugin?.close()
            sandbox.deactivate(pluginId, plugin?.classLoader)
            loadedPlugins = loadedPlugins - pluginId
        }
    }

    /**
     * Wired to [sandbox]'s [PluginSandbox.violationListener] in this class's initializer: the real
     * handling behind [PluginSandbox.reportViolation].
     *
     * A category-attributed (i.e. potential-attack) [violation] forcibly unloads [pluginId]
     * immediately. A category-less (IP-03 time-limit) [violation] is counted per plugin id via
     * [timeoutViolationCounts]; only once [MAX_TIMEOUT_VIOLATIONS] timeouts have been observed for the
     * same plugin - cumulatively, successful calls, [unload] and [reload] do not reset the count - is
     * it forcibly unloaded the same way. A single timeout alone is
     * not evidence of an attack (see [org.pcsoft.framework.pluggiat.sandbox.ThreadWatchdog]), but a
     * plugin that keeps exceeding its timeout is treated the same as one that keeps attacking the
     * sandbox, so it cannot be used to leak an unbounded number of abandoned worker threads. The
     * count is cleared only by a category-attributed violation or when the limit is reached.
     *
     * Either way, the forced unload does *not* invoke [PluginLifecycle] hooks (unlike [unload] - a
     * plugin that just attacked or persistently timed out is not trusted to run any more of its own
     * code), marks its [scanResults] entry as [PluginScanStatus.POTENTIAL_ATTACK], persists the
     * disabled reason ([SANDBOX_ATTACK_REASON] or [SANDBOX_TIMEOUT_LIMIT_REASON]) and forwards
     * [violation] to [PluginManagerConfiguration.exceptionHandlingStrategy] as a
     * [SandboxViolationException] so the host is notified - regardless of the strategy's resolved
     * action, since the unload itself is mandatory and not up to the host to decide.
     *
     * A violation for a [pluginId] that has no (longer any) [loadedPlugins] entry is handled just the
     * same, minus the parts that need the loaded plugin: its scan result is still marked, its disabled
     * reason still persisted and the host still notified, and [PluginSandbox.deactivate] is still
     * called so the sandbox drops whatever state it still holds. A plugin thread that outlived its
     * plugin's unload reaches exactly this path (its guard call is blocked fail-closed, see
     * [org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry]), and silently ignoring it
     * would both lose the attack evidence and let a plugin escape the `POTENTIAL_ATTACK` mark by
     * attacking only after it was disabled.
     *
     * The re-aggregation of the remaining plugins' extensions is deliberately *not* run on the
     * calling thread: that thread is usually the violating plugin's own (the guard call that failed
     * runs on it), and re-aggregation instantiates and calls back into other plugins' code, which
     * must never happen on a thread whose sandbox registration was just revoked - every guarded call
     * it makes would now fail. It is therefore handed to a dedicated host thread, which is joined so
     * the handler's observable effect stays synchronous, except when this thread already holds [lock]
     * (a violation raised from inside an ongoing aggregation): joining would then deadlock against
     * itself, so the re-aggregation is left to run once the outer section releases the lock.
     */
    private fun handleSandboxViolation(pluginId: String, violation: SandboxViolation) {
        val reason: String
        if (violation.category != null) {
            timeoutViolationCounts.remove(pluginId)
            reason = SANDBOX_ATTACK_REASON
        } else {
            val count = timeoutViolationCounts.merge(pluginId, 1, Int::plus) ?: 1
            if (count < MAX_TIMEOUT_VIOLATIONS) return
            timeoutViolationCounts.remove(pluginId)
            reason = SANDBOX_TIMEOUT_LIMIT_REASON
        }

        lock.withLock {
            val plugin = loadedPlugins[pluginId]
            scanResults = scanResults.map {
                if (it.manifest?.id == pluginId) it.copy(status = PluginScanStatus.POTENTIAL_ATTACK, errorMessage = violation.reason) else it
            }
            // SECURITY: the reason and the disabled flag are persisted before anything else can fail, so the
            // SECURITY: plugin stays disabled across restarts even if the rest of the teardown goes wrong.
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY, reason)
            config.persistenceStrategy.write(pluginId, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "false")
            // SECURITY: no lifecycle hooks are invoked on the way out - a plugin that just attacked the sandbox
            // SECURITY: does not get to run more of its own code, not even during the next re-aggregation.
            finishedInstancePluginIds += pluginId
            plugin?.close()
            // SECURITY: revokes the sandbox registration, so guarded calls from any surviving plugin thread are
            // SECURITY: blocked from here on (fail-closed).
            sandbox.deactivate(pluginId, plugin?.classLoader)
            loadedPlugins = loadedPlugins - pluginId
            logger.error(
                "Plugin '{}' forcibly unloaded and marked POTENTIAL_ATTACK due to a sandbox violation: {}",
                pluginId, violation.reason,
            )
        }
        reaggregateOnHostThread(pluginId)
        config.exceptionHandlingStrategy.resolve(SandboxViolationException(violation))
    }

    /**
     * Runs [reaggregateExtensions] on a fresh host thread instead of the caller's, see
     * [handleSandboxViolation] for why. The thread is joined unless the caller already holds [lock],
     * in which case joining it would deadlock and it is left to finish on its own afterwards.
     */
    private fun reaggregateOnHostThread(pluginId: String) {
        val reaggregation = Thread({ lock.withLock { reaggregateExtensions() } }, "pluggiat-sandbox-reaggregation-$pluginId")
        reaggregation.isDaemon = true
        reaggregation.start()
        if (!lock.isHeldByCurrentThread) reaggregation.join()
    }

    companion object {
        /** [ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY] value recorded by a failed [reactivate] re-check. */
        const val SECURITY_RECHECK_FAILED_REASON: String = "SECURITY_RECHECK_FAILED"

        /**
         * [ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY] value recorded when [handleSandboxViolation]
         * forcibly unloads a plugin after a category-attributed sandbox violation (see
         * [PluginScanStatus.POTENTIAL_ATTACK]).
         */
        const val SANDBOX_ATTACK_REASON: String = "SANDBOX_ATTACK"

        /**
         * [ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY] value recorded when [handleSandboxViolation]
         * forcibly unloads a plugin after [MAX_TIMEOUT_VIOLATIONS] cumulative category-less (IP-03
         * time-limit) sandbox violations.
         */
        const val SANDBOX_TIMEOUT_LIMIT_REASON: String = "SANDBOX_TIMEOUT_LIMIT"

        /**
         * Number of cumulative category-less (timeout) sandbox violations for the same plugin id
         * after which [handleSandboxViolation] forcibly unloads it, exactly like a category-attributed
         * violation - bounds the otherwise unlimited number of abandoned watchdog threads a
         * persistently timing-out plugin could accumulate. The count is not reset by successful calls,
         * [unload] or [reload].
         */
        const val MAX_TIMEOUT_VIOLATIONS: Int = 3
    }
}

/**
 * Builds a [PluginManager] from a [PluginManagerConfiguration] filled in by [applyConfig].
 */
fun pluginManager(applyConfig: PluginManagerConfiguration.() -> Unit): PluginManager {
    val config = PluginManagerConfiguration().apply(applyConfig)
    return PluginManager(config)
}
