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
 * As of IP-01, every method is a no-op (backed by [NoOpSandboxStrategy]) - the structure is in place
 * so IP-02 (API mediation), IP-03 (thread/time-limit governance) and IP-04 (process isolation) can
 * add concrete enforcement behind these same methods without any caller of [PluginSandbox] having to
 * change.
 *
 * @property strategy the concrete [PluginSandboxStrategy] backing [activate]; defaults to
 * [NoOpSandboxStrategy]
 */
class PluginSandbox(
    private val strategy: PluginSandboxStrategy = NoOpSandboxStrategy(),
) {
    private val logger = LoggerFactory.getLogger(PluginSandbox::class.java)

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
     * Reports [violation] for [pluginId] - logged as of IP-01; IP-05 adds actual handling (reporting
     * through [org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy] and a defined
     * plugin state) behind this call.
     */
    fun reportViolation(pluginId: String, violation: SandboxViolation) {
        logger.warn("Sandbox violation for plugin '{}': {}", pluginId, violation.reason)
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
