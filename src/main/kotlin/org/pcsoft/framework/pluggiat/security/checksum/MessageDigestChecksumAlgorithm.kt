/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

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
