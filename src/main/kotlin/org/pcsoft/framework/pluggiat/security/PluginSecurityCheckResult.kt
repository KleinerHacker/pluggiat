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

package org.pcsoft.framework.pluggiat.security

/**
 * Outcome of a single [PluginSecurityStrategy] check applied to one scanned plugin candidate.
 */
sealed interface PluginSecurityCheckResult {

    /** The strategy accepted the candidate. */
    data object Success : PluginSecurityCheckResult

    /**
     * The strategy rejected the candidate.
     *
     * @property reason human-readable reason for the rejection
     */
    data class Failure(val reason: String) : PluginSecurityCheckResult
}
