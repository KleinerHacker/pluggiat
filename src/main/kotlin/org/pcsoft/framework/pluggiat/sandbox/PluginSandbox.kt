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

package org.pcsoft.framework.pluggiat.sandbox

import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry
import org.pcsoft.framework.pluggiat.sandbox.process.ProcessIsolationStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.slf4j.LoggerFactory

/**
 * Single, host-wide entry point for the plugin runtime sandbox - the facade behind which every
 * sandbox mechanism (API mediation, thread/time-limit governance, process isolation, violation
 * handling) is bundled, analogous to
 * [org.pcsoft.framework.pluggiat.security.PluginSecurity] for the pre-load security chain.
 *
 * [org.pcsoft.framework.pluggiat.PluginManager] holds exactly one [PluginSandbox] instance and calls
 * only its methods; no other part of the framework addresses a [PluginSandboxStrategy] directly.
 *
 * Since IP-02, [activate] is backed by default by [AgentInstrumentationStrategy] (bytecode API
 * mediation via the `pluggiat` Java agent) and [reportViolation] performs real handling for
 * category-attributed violations (immediate unload, [org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy]
 * forwarding) via [violationListener], set by [org.pcsoft.framework.pluggiat.PluginManager]. Since
 * IP-03, [runGoverned] is backed by [watchdog] (thread/time-limit governance), and both [activate]/
 * [deactivate] additionally clear/set its per-plugin state so a governed call can never silently keep
 * running for a plugin id that was just deactivated. IP-04 (process isolation) adds further concrete
 * enforcement behind these same methods without any other caller of [PluginSandbox] having to change.
 *
 * @property strategy the concrete [PluginSandboxStrategy] backing [activate]; defaults to
 * [AgentInstrumentationStrategy]
 * @property watchdog the [ThreadWatchdog] backing [runGoverned]
 * @property processIsolation IP-04's [ProcessIsolationStrategy], a dedicated collaborator exactly
 * like [watchdog] (not reachable through the single-method [PluginSandboxStrategy] interface, since
 * it also needs to build a per-extension IPC proxy - see [ProcessIsolationStrategy.createExtensionProxy] -
 * which [org.pcsoft.framework.pluggiat.extension.ExtensionAggregator] calls directly for a plugin
 * whose effective [PluginSandboxPolicy.isolationLevel] is [SandboxIsolationLevel.PROCESS])
 */
class PluginSandbox(
    private val strategy: PluginSandboxStrategy = AgentInstrumentationStrategy(),
    private val watchdog: ThreadWatchdog = ThreadWatchdog(),
    val processIsolation: ProcessIsolationStrategy = ProcessIsolationStrategy(),
) {
    private val logger = LoggerFactory.getLogger(PluginSandbox::class.java)

    init {
        (strategy as? AgentInstrumentationStrategy)?.onViolation = ::reportViolation
        watchdog.onViolation = ::reportViolation
        processIsolation.onViolation = ::reportViolation
    }

    /**
     * Invoked by [reportViolation] for every violation, after logging - `null` by default (matching
     * IP-01's no-op behavior). [org.pcsoft.framework.pluggiat.PluginManager] sets this to its own
     * internal handler right after constructing its [org.pcsoft.framework.pluggiat.PluginManager.sandbox]
     * instance, so a violation actually leads to the plugin being unloaded rather than only logged.
     */
    var violationListener: ((pluginId: String, violation: SandboxViolation) -> Unit)? = null

    /**
     * Activates the sandbox for [loadedPlugin] under [policy] - called by
     * [org.pcsoft.framework.pluggiat.PluginManager] right after a successful
     * [org.pcsoft.framework.pluggiat.classloader.PluginLoader.load], before the plugin's extensions
     * are activated. Also clears any [ThreadWatchdog] deactivation marker left over from an earlier
     * [deactivate] of the same plugin id (see [ThreadWatchdog.activate]), so a freshly (re)loaded
     * plugin is governed normally again instead of being permanently rejected.
     */
    fun activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult {
        logger.trace(
            "Activating sandbox for plugin '{}' via strategy {} with policy(allowed={}, isolation={}, timeout={})",
            loadedPlugin.pluginId, strategy::class.simpleName, policy.allowedApiCategories, policy.isolationLevel, policy.callTimeout,
        )
        // SECURITY: clears a deactivation marker left by an earlier unload, so a freshly reloaded plugin is
        // SECURITY: governed normally again instead of being permanently rejected.
        watchdog.activate(loadedPlugin.pluginId)
        // SECURITY: process isolation gets to refuse activation first (e.g. no agent JAR for a restricted
        // SECURITY: policy), before any strategy reports success.
        processIsolation.activate(loadedPlugin, policy)
        val result = strategy.activate(loadedPlugin, policy)
        logger.trace("Sandbox activation for plugin '{}' via strategy {} resulted in {}", loadedPlugin.pluginId, strategy::class.simpleName, result)
        return result
    }

    /**
     * Runs [block] under this sandbox's governance for [pluginId] according to [policy] (e.g. a
     * lifecycle hook or extension call), delegating to [watchdog]: directly on the calling thread if
     * [PluginSandboxPolicy.callTimeout] is `null`, otherwise bounded by that timeout - see
     * [ThreadWatchdog.runGoverned].
     *
     * @throws SandboxTimeoutException if [block] does not complete within [policy]'s [PluginSandboxPolicy.callTimeout]
     * @throws SandboxDeactivatedException if [pluginId] was [deactivate]d and not [activate]d since
     */
    fun <T> runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T =
        watchdog.runGoverned(pluginId, policy, block)

    /**
     * Reports [violation] for [pluginId]: always logged, then forwarded to [violationListener] (if
     * any) so it can act on it (see [org.pcsoft.framework.pluggiat.PluginManager]'s handler, which
     * immediately unloads the plugin for a category-attributed - i.e. potential-attack - violation
     * and forwards it to [org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy]). A
     * category-less violation (e.g. an IP-03 time-limit violation) is logged the same way but left
     * for [violationListener] to decide how to react.
     */
    fun reportViolation(pluginId: String, violation: SandboxViolation) {
        // SECURITY: a category-attributed violation means a guarded API was actually attempted - logged at
        // SECURITY: WARN with an explicit attack marker, because it is evidence, not a mishap.
        if (violation.category != null) {
            logger.warn(
                "SECURITY WARNING - potential attack: plugin '{}' violated its sandbox policy (category={}): {}",
                pluginId, violation.category, violation.reason,
            )
        } else {
            logger.warn("Sandbox violation for plugin '{}': {}", pluginId, violation.reason)
        }
        logger.trace("Forwarding sandbox violation for plugin '{}' to violationListener={}", pluginId, violationListener != null)
        violationListener?.invoke(pluginId, violation)
    }

    /**
     * Releases any sandbox-side state held for [pluginId], called by
     * [org.pcsoft.framework.pluggiat.PluginManager] as part of unloading/reloading a plugin: shuts
     * down [pluginId]'s [ThreadWatchdog] executor (if any) and marks it deactivated so a governed call
     * racing against this deactivation cannot silently keep it running (see [ThreadWatchdog.deactivate]),
     * and - if [classLoader] is given - revokes its [SandboxGuardRegistry] registration.
     *
     * Revoking is deliberately not the same as forgetting: from then on every guarded call made from
     * one of that class loader's classes is blocked outright, instead of finding no registration and
     * being waved through (see [SandboxGuardRegistry.revoke]). A plugin thread that survives its
     * plugin's unload therefore loses its guarded APIs at the moment of unload rather than gaining
     * them.
     *
     * @param classLoader the deactivated plugin's class loader, if known; `null` skips the
     * [SandboxGuardRegistry] revocation (e.g. when no plugin was ever actually loaded for [pluginId])
     */
    fun deactivate(pluginId: String, classLoader: ClassLoader? = null) {
        logger.trace("Deactivating sandbox for plugin '{}' (classLoader known={})", pluginId, classLoader != null)
        // SECURITY: marks the id deactivated and shuts its executor down, so a governed call racing with this
        // SECURITY: deactivation cannot keep running afterwards.
        watchdog.deactivate(pluginId)
        // SECURITY: kills the subprocess of a process-isolated plugin - its own JVM would otherwise outlive
        // SECURITY: the unload entirely.
        processIsolation.stop(pluginId)
        // SECURITY: revoke, not release: every later guarded call from this loader is blocked (fail-closed).
        classLoader?.let(SandboxGuardRegistry::revoke)
    }

    companion object {
        /**
         * The effective [PluginSandboxPolicy] for [location]: its own [PluginLocation.sandboxOverride]
         * if set, otherwise the entry for its [PluginLocationType] in [sandboxPolicies], otherwise
         * [PluginSandboxPolicy.UNRESTRICTED] - the single source of truth for this resolution rule,
         * mirroring [org.pcsoft.framework.pluggiat.security.PluginSecurity.effectiveChain]. Unlike the
         * security chain, an unconfigured sandbox never fails a load - it simply stays unrestricted.
         */
        fun effectivePolicy(
            location: PluginLocation,
            sandboxPolicies: Map<PluginLocationType, PluginSandboxPolicy>,
        ): PluginSandboxPolicy {
            val override = location.sandboxOverride
            val policy = override ?: sandboxPolicies[location.type] ?: PluginSandboxPolicy.UNRESTRICTED
            LoggerFactory.getLogger(PluginSandbox::class.java).trace(
                "Resolved effective sandbox policy for location '{}' (type={}): source={}",
                location.path, location.type,
                if (override != null) "override" else if (sandboxPolicies.containsKey(location.type)) "default" else "unrestricted-fallback",
            )
            return policy
        }
    }
}
