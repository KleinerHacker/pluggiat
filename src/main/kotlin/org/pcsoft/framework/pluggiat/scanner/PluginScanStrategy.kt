package org.pcsoft.framework.pluggiat.scanner

/**
 * Scans a single [PluginLocation] for plugin candidates.
 *
 * A load mode is not represented as a separate enum; it is fully expressed by the concrete
 * [PluginScanStrategy] implementation used by a [PluginLocation].
 */
interface PluginScanStrategy {
    /**
     * Scans [location] and returns one [PluginScanResult] per plugin candidate found, both valid
     * and invalid ones.
     */
    fun scan(location: PluginLocation): List<PluginScanResult>
}
