package org.pcsoft.framework.pluggiat.security

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
 */
class PluginSecurity {
    private val logger = LoggerFactory.getLogger(PluginSecurity::class.java)

    /**
     * Evaluates the security chain for [result].
     *
     * The effective chain is [PluginScanResult.location]'s `securityOverride` if non-empty,
     * otherwise the entry for the location's [PluginLocationType] in [defaultSecurityChains] (or
     * an empty list if none is configured there either).
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
        val location = result.location
        val chain = location.securityOverride.ifEmpty { defaultSecurityChains[location.type] ?: emptyList() }
        check(chain.isNotEmpty()) {
            "No security strategies configured for plugin location '${location.path}' (type=${location.type}); " +
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
}
