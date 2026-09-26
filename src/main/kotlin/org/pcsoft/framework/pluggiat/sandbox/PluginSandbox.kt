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
 * forwarding) via [violationListener], set by [org.pcsoft.framework.pluggiat.PluginManager]. IP-03
 * (thread/time-limit governance) and IP-04 (process isolation) add further concrete enforcement
 * behind these same methods without any other caller of [PluginSandbox] having to change.
 *
 * @property strategy the concrete [PluginSandboxStrategy] backing [activate]; defaults to
 * [AgentInstrumentationStrategy]
 */
class PluginSandbox(
    private val strategy: PluginSandboxStrategy = AgentInstrumentationStrategy(),
) {
    private val logger = LoggerFactory.getLogger(PluginSandbox::class.java)

    init {
        (strategy as? AgentInstrumentationStrategy)?.onViolation = ::reportViolation
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
     * are activated. A no-op returning [SandboxCheckResult.Success] as of IP-01.
     */
    fun activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult =
        strategy.activate(loadedPlugin, policy)

    /**
     * Runs [block] under this sandbox's governance for [pluginId] according to [policy] (e.g. a
     * lifecycle hook or extension call) - a direct, unmodified invocation of [block] as of IP-01;
     * IP-03 adds actual thread/time-limit governance behind this call.
     */
    fun <T> runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T = block()

    /**
     * Reports [violation] for [pluginId]: always logged, then forwarded to [violationListener] (if
     * any) so it can act on it (see [org.pcsoft.framework.pluggiat.PluginManager]'s handler, which
     * immediately unloads the plugin for a category-attributed - i.e. potential-attack - violation
     * and forwards it to [org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy]). A
     * category-less violation (e.g. a future IP-03 time-limit violation) is logged the same way but
     * left for [violationListener] to decide how to react.
     */
    fun reportViolation(pluginId: String, violation: SandboxViolation) {
        if (violation.category != null) {
            logger.warn(
                "SECURITY WARNING - potential attack: plugin '{}' violated its sandbox policy (category={}): {}",
                pluginId, violation.category, violation.reason,
            )
        } else {
            logger.warn("Sandbox violation for plugin '{}': {}", pluginId, violation.reason)
        }
        violationListener?.invoke(pluginId, violation)
    }

    /**
     * Releases any sandbox-side state held for [pluginId], called by
     * [org.pcsoft.framework.pluggiat.PluginManager] as part of unloading/reloading a plugin. A no-op
     * as of IP-01.
     */
    fun deactivate(pluginId: String) {
        // No sandbox-side state to release yet - concrete strategies (IP-02/IP-03/IP-04) add cleanup here.
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
        ): PluginSandboxPolicy =
            location.sandboxOverride ?: sandboxPolicies[location.type] ?: PluginSandboxPolicy.UNRESTRICTED
    }
}
