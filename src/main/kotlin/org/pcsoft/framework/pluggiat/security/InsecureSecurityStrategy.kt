package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.slf4j.LoggerFactory

/**
 * A [PluginSecurityStrategy] that performs no check at all and always accepts the candidate.
 *
 * Naming this strategy "insecure" is deliberate: a location's fallback chain starts out empty by
 * default (see [PluginSecurityChainEvaluator]), so opting into no security check at all requires
 * explicitly adding this strategy, rather than it being an implicit default.
 */
class InsecureSecurityStrategy : PluginSecurityStrategy {
    private val logger = LoggerFactory.getLogger(InsecureSecurityStrategy::class.java)

    override fun check(result: PluginScanResult): PluginSecurityCheckResult {
        logger.debug("No security check performed for candidate '{}', passed through as insecure", result.path)
        return PluginSecurityCheckResult.Success
    }
}
