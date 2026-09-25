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
