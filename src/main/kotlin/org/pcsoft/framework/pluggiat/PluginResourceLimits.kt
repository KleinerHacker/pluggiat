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

package org.pcsoft.framework.pluggiat

/**
 * Hard upper bounds applied while a plugin candidate's own bytes are read, unpacked and parsed.
 *
 * Everything below this point processes data a plugin author controls, before any security check has
 * decided whether that plugin is trusted at all. Without limits, the mere *reading* of a candidate is
 * a denial-of-service vector that needs no signature, no checksum and no successful load: a file whose
 * declared size exceeds the heap, a small archive that unpacks to gigabytes (a "zip bomb"), a JAR
 * nested into a JAR nested into a JAR, or a manifest of unbounded length all take the host JVM down
 * with an `OutOfMemoryError` long before the candidate would have been rejected.
 *
 * The values are deliberately generous - far above any realistic plugin - so that they only ever stop
 * abuse, never legitimate use. They are fixed rather than configurable: a limit a host can raise is a
 * limit an attacker will ask it to raise.
 *
 * Violations surface as [org.pcsoft.framework.pluggiat.scanner.PluginContentLimitExceededException],
 * which makes the candidate a
 * [org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.SECURITY_PROBLEM] rather than a crash.
 */
object PluginResourceLimits {

    /** Maximum size of one candidate file (a single JAR or a ZIP of JARs). */
    const val MAX_CANDIDATE_FILE_SIZE_BYTES: Long = 256L * 1024 * 1024

    /**
     * Maximum number of bytes one candidate may unpack to in total, across every (possibly nested)
     * archive - the actual zip-bomb bound, enforced while reading rather than from the archive's own
     * declared sizes, which an attacker writes.
     */
    const val MAX_UNPACKED_TOTAL_SIZE_BYTES: Long = 512L * 1024 * 1024

    /** Maximum unpacked size of a single archive entry. */
    const val MAX_UNPACKED_ENTRY_SIZE_BYTES: Long = 128L * 1024 * 1024

    /**
     * Maximum archive nesting depth, counting the candidate itself as depth 0. A `ZIP_JAR` candidate
     * (a ZIP of JARs) legitimately reaches depth 1; anything beyond this is a recursion bomb, not a
     * packaging style.
     */
    const val MAX_NESTING_DEPTH: Int = 4

    /** Maximum size of a plugin manifest. */
    const val MAX_MANIFEST_SIZE_BYTES: Long = 1L * 1024 * 1024
}
