package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.scanner.PluginScanResult

/**
 * A single security check applied to a scanned plugin candidate (e.g. no check, signature,
 * checksum) as part of a location's ordered fallback chain.
 *
 * Custom, framework-external implementations can be added to a location's fallback chain without
 * changing any framework code.
 */
interface PluginSecurityStrategy {

    /**
     * Checks [result], which is guaranteed to have [org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.LOADED]
     * as its status (i.e. its manifest was already successfully resolved).
     */
    fun check(result: PluginScanResult): PluginSecurityCheckResult
}
