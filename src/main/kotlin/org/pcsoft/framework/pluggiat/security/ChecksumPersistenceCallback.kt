package org.pcsoft.framework.pluggiat.security

/**
 * Persists a newly accepted checksum for a plugin.
 *
 * This is invoked by the host as a consequence of a force-load (see IP-05/IP-07's `PluginLoader`
 * force-load entry point), never by [ChecksumSecurityStrategy] or
 * [PluginSecurityChainEvaluator] themselves - the chain evaluation only ever reports a failed
 * check via `org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.SECURITY_PROBLEM`; whether and
 * when the user is asked for approval, and whether that approval leads to a force-load whose
 * resulting checksum should be persisted, is entirely the host's decision.
 *
 * Must be a cheap, synchronous call that does not block the (force-)load path.
 */
fun interface ChecksumPersistenceCallback {
    fun persist(pluginId: String, checksum: String)
}
