package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import java.nio.file.Path

/**
 * A single configured location plugins are scanned from.
 *
 * @property path directory to scan
 * @property type whether this location is shipped with the host application or contributed externally
 * @property scanStrategy the load mode of this location, expressed as the concrete scan strategy to use
 * @property securityOverride ordered fallback chain of security strategies overriding the default
 * chain for this location; empty means the default chain applies (see IP-04)
 */
data class PluginLocation(
    val path: Path,
    val type: PluginLocationType,
    val scanStrategy: PluginScanStrategy = ZipJarScanStrategy(),
    val securityOverride: List<PluginSecurityStrategy> = emptyList(),
)
