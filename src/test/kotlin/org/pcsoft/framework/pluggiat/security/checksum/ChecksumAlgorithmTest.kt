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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChecksumAlgorithmTest {

    /**
     * Use case: a [MessageDigestChecksumAlgorithm] configured for `"MD5"` computes the well-known
     * MD5 digest of the empty input.
     */
    @Test
    fun `computes the MD5 digest of empty input`() {
        val algorithm = MessageDigestChecksumAlgorithm("MD5")

        assertEquals("MD5", algorithm.id)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", algorithm.digest(ByteArray(0)))
    }

    /**
     * Use case: a [MessageDigestChecksumAlgorithm] configured for `"SHA-256"` computes the
     * well-known SHA-256 digest of the empty input.
     */
    @Test
    fun `computes the SHA-256 digest of empty input`() {
        val algorithm = MessageDigestChecksumAlgorithm("SHA-256")

        assertEquals("SHA-256", algorithm.id)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            algorithm.digest(ByteArray(0)),
        )
    }

    /**
     * Use case: a [MessageDigestChecksumAlgorithm] configured for `"SHA-512"` computes the
     * well-known SHA-512 digest of the empty input - SHA-512 is the default algorithm used by
     * `org.pcsoft.framework.pluggiat.security.ChecksumSecurityStrategy` and
     * `org.pcsoft.framework.pluggiat.security.SignatureSecurityStrategy`.
     */
    @Test
    fun `computes the SHA-512 digest of empty input`() {
        val algorithm = MessageDigestChecksumAlgorithm("SHA-512")

        assertEquals("SHA-512", algorithm.id)
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            algorithm.digest(ByteArray(0)),
        )
    }
}
