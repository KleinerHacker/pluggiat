package org.pcsoft.framework.pluggiat.security.checksum

/**
 * A checksum algorithm usable by `org.pcsoft.framework.pluggiat.security.ChecksumSecurityStrategy`
 * and the checksum-list mechanism of `org.pcsoft.framework.pluggiat.security.SignatureSecurityStrategy`.
 *
 * Framework-external implementations can be added without any framework changes.
 */
interface ChecksumAlgorithm {

    /**
     * Short, stable identifier of this algorithm (e.g. `"SHA-512"`), used in log/error messages.
     */
    val id: String

    /**
     * Computes the checksum of [bytes], hex-encoded (lowercase).
     */
    fun digest(bytes: ByteArray): String
}
