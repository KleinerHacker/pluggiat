package org.pcsoft.framework.pluggiat.security

/**
 * Outcome of a single [PluginSecurityStrategy] check applied to one scanned plugin candidate.
 */
sealed interface PluginSecurityCheckResult {

    /** The strategy accepted the candidate. */
    data object Success : PluginSecurityCheckResult

    /**
     * The strategy rejected the candidate.
     *
     * @property reason human-readable reason for the rejection
     */
    data class Failure(val reason: String) : PluginSecurityCheckResult
}
