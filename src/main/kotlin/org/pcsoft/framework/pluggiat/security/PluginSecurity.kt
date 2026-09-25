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
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContentReader
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
     * Evaluates the security chain for [result], re-reading each strategy's required bytes from
     * disk itself. Prefer the [PinnedPluginContent] overload wherever the candidate's bytes were
     * already pinned, to close the TOCTOU window between this check and the later
     * `org.pcsoft.framework.pluggiat.classloader.PluginLoader.load` call.
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
    ): PluginSecurityCheckResult = evaluateChain(result, defaultSecurityChains) { strategy -> strategy.check(result) }

    /**
     * Evaluates the security chain for [result] exactly like [evaluate], but checks every strategy
     * against the already-pinned [pinnedContent] instead of letting each strategy re-read the
     * candidate from disk itself - the bytes checked here are then reused, unchanged, for the
     * candidate's actual load.
     */
    fun evaluate(
        result: PluginScanResult,
        pinnedContent: PinnedPluginContent,
        defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
    ): PluginSecurityCheckResult = evaluateChain(result, defaultSecurityChains) { strategy -> strategy.check(result, pinnedContent) }

    private fun evaluateChain(
        result: PluginScanResult,
        defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
        checkWith: (PluginSecurityStrategy) -> PluginSecurityCheckResult,
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
            when (val checkResult = checkWith(strategy)) {
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
        val rescanned = rescan(location, path) ?: return PluginSecurityCheckResult.Failure(rescanFailureMessage(location, path))
        return evaluate(rescanned, defaultSecurityChains)
    }

    /**
     * Like [reevaluate], but also pins the candidate's bytes at [path] once (see
     * [PinnedPluginContentReader]) and checks the chain against that pinned content, so the returned
     * [PinnedPluginContent] can be reused unchanged for the candidate's actual (re-)load - closing
     * the same TOCTOU window on reactivation that [org.pcsoft.framework.pluggiat.scanner.PluginScanner]
     * closes on the initial scan.
     *
     * @return the check result together with the pinned content on success, or a failed check result
     * with `null` content if [path] is no longer a valid candidate or the chain rejects it
     */
    fun reevaluateAndPin(
        location: PluginLocation,
        path: Path,
        defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>>,
    ): PinnedReevaluationResult {
        val rescanned = rescan(location, path)
            ?: return PinnedReevaluationResult(PluginSecurityCheckResult.Failure(rescanFailureMessage(location, path)), null)

        val pinnedContent = PinnedPluginContentReader.read(path)
        val checkResult = evaluate(rescanned, pinnedContent, defaultSecurityChains)
        return PinnedReevaluationResult(checkResult, pinnedContent.takeIf { checkResult is PluginSecurityCheckResult.Success })
    }

    private fun rescan(location: PluginLocation, path: Path): PluginScanResult? {
        val rescanned = location.scanStrategy.scan(location).first { it.path == path }
        return rescanned.takeIf { it.status == PluginScanStatus.LOADED }
    }

    private fun rescanFailureMessage(location: PluginLocation, path: Path): String {
        val rescanned = location.scanStrategy.scan(location).first { it.path == path }
        return "Candidate at '$path' is no longer a valid plugin candidate on re-check: ${rescanned.errorMessage} (${rescanned.status})"
    }

    /**
     * Outcome of [reevaluateAndPin].
     *
     * @property checkResult the security check outcome
     * @property pinnedContent the candidate's pinned bytes, `null` unless [checkResult] is [PluginSecurityCheckResult.Success]
     */
    data class PinnedReevaluationResult(
        val checkResult: PluginSecurityCheckResult,
        val pinnedContent: PinnedPluginContent?,
    )

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
