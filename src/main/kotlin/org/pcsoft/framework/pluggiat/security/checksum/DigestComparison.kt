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
 * Compares two hex-encoded digests ([expected], [actual]) case-insensitively in constant time via
 * [MessageDigest.isEqual], instead of [String.equals], to avoid a timing side channel on the
 * digest comparison itself.
 */
fun digestsEqual(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
        expected.lowercase().toByteArray(Charsets.US_ASCII),
        actual.lowercase().toByteArray(Charsets.US_ASCII),
    )
