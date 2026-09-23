package org.pcsoft.framework.pluggiat.security.checksum

import java.security.MessageDigest

/**
 * A [ChecksumAlgorithm] backed by a [java.security.MessageDigest] algorithm name understood by the
 * JVM (see `java.security.MessageDigest#getInstance`), e.g. `"MD5"`, `"SHA-256"` or `"SHA-512"`.
 */
class MessageDigestChecksumAlgorithm(override val id: String) : ChecksumAlgorithm {
    override fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance(id).digest(bytes).joinToString("") { "%02x".format(it) }
}
