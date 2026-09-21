package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityChainEvaluator
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import org.slf4j.LoggerFactory

/**
 * Scans a set of configured [PluginLocation]s for plugin candidates.
 *
 * @property defaultSecurityChains the default security fallback chain applied to a location per
 * [PluginLocationType], used whenever a location does not configure its own
 * [PluginLocation.securityOverride]; empty by default, meaning a location without an explicit
 * override has no default chain to fall back to (see [PluginSecurityChainEvaluator])
 */
class PluginScanner(
    private val defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>> = emptyMap(),
    private val securityChainEvaluator: PluginSecurityChainEvaluator = PluginSecurityChainEvaluator(),
) {
    private val logger = LoggerFactory.getLogger(PluginScanner::class.java)

    /**
     * Scans all [locations] and returns the combined list of [PluginScanResult]s across all of
     * them, both valid and invalid candidates.
     */
    fun scan(locations: List<PluginLocation>): List<PluginScanResult> =
        locations.flatMap { location -> scanLocation(location) }

    private fun scanLocation(location: PluginLocation): List<PluginScanResult> {
        logger.info(
            "Scanning plugin location '{}' with strategy {} (type={})",
            location.path, location.scanStrategy::class.simpleName, location.type,
        )

        val results = location.scanStrategy.scan(location)
        return results.map { result -> applySecurityCheck(result) }
    }

    private fun applySecurityCheck(result: PluginScanResult): PluginScanResult {
        if (result.status != PluginScanStatus.LOADED) {
            logger.warn("Invalid plugin candidate at '{}': {} ({})", result.path, result.errorMessage, result.status)
            return result
        }

        return when (val checkResult = securityChainEvaluator.evaluate(result, defaultSecurityChains)) {
            is PluginSecurityCheckResult.Success -> result
            is PluginSecurityCheckResult.Failure -> {
                logger.warn("Security problem for plugin candidate at '{}': {}", result.path, checkResult.reason)
                result.copy(manifest = null, status = PluginScanStatus.SECURITY_PROBLEM, errorMessage = checkResult.reason)
            }
        }
    }
}
