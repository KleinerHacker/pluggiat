package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.slf4j.LoggerFactory

/**
 * Evaluates a plugin location's ordered fallback chain of [PluginSecurityStrategy]s against a
 * single scanned plugin candidate.
 */
class PluginSecurityChainEvaluator {
    private val logger = LoggerFactory.getLogger(PluginSecurityChainEvaluator::class.java)

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
}
