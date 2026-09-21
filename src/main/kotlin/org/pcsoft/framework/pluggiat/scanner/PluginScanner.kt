package org.pcsoft.framework.pluggiat.scanner

import org.slf4j.LoggerFactory

/**
 * Scans a set of configured [PluginLocation]s for plugin candidates.
 */
class PluginScanner {
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
        for (result in results) {
            if (result.status != PluginScanStatus.LOADED) {
                logger.warn("Invalid plugin candidate at '{}': {} ({})", result.path, result.errorMessage, result.status)
            }
        }
        return results
    }
}
