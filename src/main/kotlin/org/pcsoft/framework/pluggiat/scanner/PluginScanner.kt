package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.security.PluginSecurity
import org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import org.slf4j.LoggerFactory

/**
 * Scans a set of configured [PluginLocation]s for plugin candidates.
 *
 * @property defaultSecurityChains the default security fallback chain applied to a location per
 * [PluginLocationType], used whenever a location does not configure its own
 * [PluginLocation.securityOverride]; empty by default, meaning a location without an explicit
 * override has no default chain to fall back to (see [PluginSecurity])
 * @property security evaluates each scanned candidate's security fallback chain
 */
class PluginScanner(
    private val defaultSecurityChains: Map<PluginLocationType, List<PluginSecurityStrategy>> = emptyMap(),
    private val security: PluginSecurity = PluginSecurity(),
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

        return when (val checkResult = security.evaluate(result, defaultSecurityChains)) {
            is PluginSecurityCheckResult.Success -> result
            is PluginSecurityCheckResult.Failure -> {
                logger.warn("Security problem for plugin candidate at '{}': {}", result.path, checkResult.reason)
                // the manifest is kept (unlike MANIFEST_NOT_FOUND/MANIFEST_INVALID) so a host can
                // still force-load this candidate via org.pcsoft.framework.pluggiat.PluginManager.forceLoad
                result.copy(status = PluginScanStatus.SECURITY_PROBLEM, errorMessage = checkResult.reason)
            }
        }
    }
}
