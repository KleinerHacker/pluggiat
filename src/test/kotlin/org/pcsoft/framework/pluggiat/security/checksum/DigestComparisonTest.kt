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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Developer tests for [digestsEqual]: the constant-time (via [java.security.MessageDigest.isEqual])
 * replacement for a plain, timing-sensitive `String.equals` digest comparison.
 */
class DigestComparisonTest {

    /**
     * Use case: two identical hex digests are reported as equal.
     */
    @Test
    fun `identical digests are equal`() {
        assertTrue(digestsEqual("abcd1234", "abcd1234"))
    }

    /**
     * Use case: the comparison is case-insensitive, exactly like the previous `String.equals(ignoreCase = true)`
     * it replaces, so a differently-cased but otherwise identical digest still matches.
     */
    @Test
    fun `digests differing only in case are equal`() {
        assertTrue(digestsEqual("ABCD1234", "abcd1234"))
    }

    /**
     * Use case: two digests of the same length but differing content are reported as not equal.
     */
    @Test
    fun `different digests are not equal`() {
        assertFalse(digestsEqual("abcd1234", "abcd1235"))
    }

    /**
     * Use case: digests of different length are reported as not equal, rather than throwing (the
     * underlying `MessageDigest.isEqual` handles differing lengths safely).
     */
    @Test
    fun `digests of different length are not equal`() {
        assertFalse(digestsEqual("abcd", "abcd1234"))
    }
}
