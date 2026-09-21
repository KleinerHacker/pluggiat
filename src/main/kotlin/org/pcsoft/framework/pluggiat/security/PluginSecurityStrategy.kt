package org.pcsoft.framework.pluggiat.security

/**
 * A single security check applied to a scanned plugin candidate (e.g. no check, signature,
 * checksum) as part of a location's ordered fallback chain.
 *
 * This is currently a marker interface without any operations; the check operation and the
 * fallback-chain evaluation itself are introduced together with the concrete strategies.
 */
interface PluginSecurityStrategy
