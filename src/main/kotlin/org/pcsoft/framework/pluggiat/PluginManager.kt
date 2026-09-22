package org.pcsoft.framework.pluggiat

import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginDependencyStrategy
import org.pcsoft.framework.pluggiat.classloader.PluginLoadResult
import org.pcsoft.framework.pluggiat.classloader.PluginLoader
import org.pcsoft.framework.pluggiat.classloader.SdkWhitelistEntry
import org.pcsoft.framework.pluggiat.classloader.UnrestrictedPluginDependencyStrategy
import org.pcsoft.framework.pluggiat.exception.DefaultExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.extension.ExtensionAggregator
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.persistence.NoPersistenceStrategy
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanner
import org.pcsoft.framework.pluggiat.security.PluginSecurity
import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Path

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
 */
class PluginManagerConfiguration {
    val pluginLocations: MutableList<PluginLocation> = mutableListOf()
    val defaultSecurityChains: MutableMap<PluginLocationType, List<PluginSecurityStrategy>> = mutableMapOf()
    var dependencyStrategy: PluginDependencyStrategy = UnrestrictedPluginDependencyStrategy()
    var persistenceStrategy: PluginPersistenceStrategy = NoPersistenceStrategy()
    var exceptionHandlingStrategy: ExceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    val sdkWhitelist: MutableList<SdkWhitelistEntry> = mutableListOf()
    var hostVersion: String? = null

    /**
     * Adds [location] to [pluginLocations].
     */
    fun location(location: PluginLocation) {
        pluginLocations += location
    }

    /**
     * Sets the default security fallback chain for [type] in [defaultSecurityChains].
     */
    fun defaultSecurityChain(type: PluginLocationType, chain: List<PluginSecurityStrategy>) {
        defaultSecurityChains[type] = chain
    }

    /**
     * Adds [entry] to [sdkWhitelist].
     */
    fun sdkWhitelistEntry(entry: SdkWhitelistEntry) {
        sdkWhitelist += entry
    }
}

/**
 * Central, host-wide entry point of the plugin framework.
 *
 * Built via the [pluginManager] Kotlin builder DSL from a [PluginManagerConfiguration]. Bundles the framework's
 * host-wide configuration (plugin locations, default security chains, dependency strategy,
 * persistence strategy, exception handling strategy, SDK whitelist, host version) in one place and
 * exposes pre-wired [scanner], [security] and [loader] instances built from it.
 *
 * For now, [PluginManager] is a configuration/access facade only - the overall scan-load-enable
 * orchestration flow (id collision handling, `minVersion` enforcement, ...) is implemented as part
 * of IP-07. The existing constructor parameters of [PluginScanner]/[PluginLoader] remain usable
 * directly in addition to going through [PluginManager]. [reactivate] is the one exception: it is
 * already a small orchestration step (security re-check, then load), needed because IP-06 requires
 * one central place enforcing "re-check before reload".
 *
 * @property config the configuration this instance was built from
 */
class PluginManager(val config: PluginManagerConfiguration) {
    private val logger = LoggerFactory.getLogger(PluginManager::class.java)

    /**
     * A [PluginSecurity] instance, also used to pre-wire [scanner].
     */
    val security: PluginSecurity = PluginSecurity()

    /**
     * A [PluginScanner] pre-wired with [PluginManagerConfiguration.defaultSecurityChains].
     */
    val scanner: PluginScanner = PluginScanner(config.defaultSecurityChains, security)

    /**
     * A [PluginLoader] pre-wired with [PluginManagerConfiguration.sdkWhitelist].
     */
    val loader: PluginLoader = PluginLoader(sdkWhitelist = config.sdkWhitelist)

    /**
     * Reactivates a previously disabled plugin: re-checks [security] against the candidate at
     * [path] within [location] and, only on success, reloads it via [loader] and persists it as
     * enabled again via [PluginManagerConfiguration.persistenceStrategy].
     *
     * A failed re-check keeps the plugin disabled (its disable reason is updated to reflect the
     * failed re-check) and never falls back to a force-load - a host that wants to force-load
     * despite the failure calls [loader] directly itself, as for any other force-load.
     */
    fun reactivate(
        location: PluginLocation,
        path: Path,
        manifest: PluginManifest,
        dependencies: Map<String, LoadedPlugin> = emptyMap(),
    ): PluginLoadResult {
        val check = security.reevaluate(location, path, config.defaultSecurityChains)
        if (check is PluginSecurityCheckResult.Failure) {
            logger.warn("Security re-check failed for plugin '{}' on reactivation: {}", manifest.id, check.reason)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY, SECURITY_RECHECK_FAILED_REASON)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "false")
            return PluginLoadResult.Invalid(manifest.id, "Security re-check failed on reactivation: ${check.reason}")
        }

        val result = loader.load(path, manifest, dependencies)
        if (result is PluginLoadResult.Loaded) {
            logger.info("Plugin '{}' passed the security re-check and was reloaded, persisted as enabled again", manifest.id)
            config.persistenceStrategy.write(manifest.id, ExtensionAggregator.ENABLED_PERSISTENCE_KEY, "true")
        }
        return result
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
