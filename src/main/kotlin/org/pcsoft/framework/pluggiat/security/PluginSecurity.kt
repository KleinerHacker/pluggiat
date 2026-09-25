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

package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.persistence.NoPersistenceStrategy
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Evaluates a plugin location's ordered fallback chain of [PluginSecurityStrategy]s against a
 * scanned plugin candidate.
 *
 * Public API, freely usable by a host directly (analogous to
 * `org.pcsoft.framework.pluggiat.classloader.PluginLoader`), independent of any reactivation flow -
 * not only used internally by `org.pcsoft.framework.pluggiat.scanner.PluginScanner`.
 *
 * @property persistenceStrategy source of truth for a plugin's [SECURITY_EXCEPTION_KEY] flag, set by
 * `org.pcsoft.framework.pluggiat.PluginManager.forceLoad(pluginId, persistException = true)`
 */
class PluginSecurity(
    private val persistenceStrategy: PluginPersistenceStrategy = NoPersistenceStrategy(),
) {
    private val logger = LoggerFactory.getLogger(PluginSecurity::class.java)

    /**
     * Evaluates the security chain for [result].
     *
     * If [SECURITY_EXCEPTION_KEY] is persisted as `"true"` for [result]'s plugin id, the chain is
     * skipped entirely and this returns [PluginSecurityCheckResult.Success] - a WARN is logged every
     * time this happens, since it silently bypasses every configured strategy for that plugin.
     *
     * Otherwise, the effective chain is [PluginScanResult.location]'s `securityOverride` if
     * non-empty, otherwise the entry for the location's [PluginLocationType] in
     * [defaultSecurityChains] (or an empty list if none is configured there either).
     *
     * The chain is checked in configured order; the first successful strategy ends the check
     * positively. A security problem is only reported once ALL strategies of the chain have
     * failed.
     *
     * @param result the scanned plugin candidate to evaluate
     * @param defaultSecurityChains the default security fallback chain per [PluginLocationType],
     * consulted when [result]'s location has no `securityOverride` of its own
     * @throws IllegalStateException if the effective chain is empty, i.e. no security strategy at
     * all is configured for this location, neither as override nor as type default
     */
    fun evaluate(
        result: PluginScanResult,
        defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
    ): PluginSecurityCheckResult {
        val pluginId = result.manifest?.id
        if (pluginId != null && persistenceStrategy.read(pluginId, SECURITY_EXCEPTION_KEY) == "true") {
            logger.warn("Security exception active for plugin '{}', skipping the security chain entirely", pluginId)
            return PluginSecurityCheckResult.Success
        }

        val chain = effectiveChain(result.location, defaultSecurityChains)
        check(chain.isNotEmpty()) {
            "No security strategies configured for plugin location '${result.location.path}' (type=${result.location.type}); " +
                "either set an override on the location or a default chain for its type"
        }

        logger.trace("Evaluating chain of {} strategy(ies) for '{}': {}", chain.size, result.path, chain.map { it::class.simpleName })
        val failureReasons = mutableListOf<String>()
        for (strategy in chain) {
            when (val checkResult = strategy.check(result)) {
                is PluginSecurityCheckResult.Success -> {
                    logger.trace("Security strategy {} succeeded for '{}', chain ends positively", strategy::class.simpleName, result.path)
                    return checkResult
                }
                is PluginSecurityCheckResult.Failure -> {
                    logger.warn("Security strategy {} failed for '{}': {}", strategy::class.simpleName, result.path, checkResult.reason)
                    failureReasons += checkResult.reason
                }
            }
        }

        return PluginSecurityCheckResult.Failure(
            "All ${chain.size} security strategies failed for '${result.path}': ${failureReasons.joinToString("; ")}",
        )
    }

    /**
     * Re-reads the candidate at [path] within [location] via its [PluginLocation.scanStrategy] and
     * re-[evaluate]s it against [defaultSecurityChains].
     *
     * Used as the first step of a plugin reactivation flow (re-check before
     * `org.pcsoft.framework.pluggiat.classloader.PluginLoader.load` is called again), but callable
     * by a host independently of any reactivation as well.
     *
     * @param location the location to re-scan [path] within
     * @param path candidate path to re-scan and re-evaluate
     * @param defaultSecurityChains the default security fallback chain per [PluginLocationType],
     * consulted when [location] has no `securityOverride` of its own
     * @throws NoSuchElementException if [location]'s scan strategy no longer reports a candidate at [path]
     */
    fun reevaluate(
        location: PluginLocation,
        path: Path,
        defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
    ): PluginSecurityCheckResult {
        val rescanned = location.scanStrategy.scan(location).first { it.path == path }
        if (rescanned.status != PluginScanStatus.LOADED) {
            return PluginSecurityCheckResult.Failure(
                "Candidate at '$path' is no longer a valid plugin candidate on re-check: ${rescanned.errorMessage} (${rescanned.status})",
            )
        }
        return evaluate(rescanned, defaultSecurityChains)
    }

    companion object {
        /**
         * [PluginPersistenceStrategy] key holding a plugin's persistent security exception flag
         * (`"true"`/absent). Set by `org.pcsoft.framework.pluggiat.PluginManager.forceLoad(pluginId,
         * persistException = true)`; once set, [evaluate] skips the security chain entirely for that
         * plugin id until the host clears the key itself.
         */
        const val SECURITY_EXCEPTION_KEY: String = "securityException"

        /**
         * The effective security fallback chain for [location]: its own `securityOverride` if
         * non-empty, otherwise the entry for its [PluginLocationType] in [defaultSecurityChains] (or
         * an empty list if none is configured there either). The single source of truth for this
         * resolution rule, also used by `org.pcsoft.framework.pluggiat.PluginManager.write`.
         */
        fun effectiveChain(
            location: PluginLocation,
            defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
        ): List<PluginSecurityStrategy> =
            location.securityOverride.ifEmpty { defaultSecurityChains[location.type] ?: emptyList() }
    }
}
