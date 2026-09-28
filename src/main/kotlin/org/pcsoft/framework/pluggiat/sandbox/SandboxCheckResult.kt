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

package org.pcsoft.framework.pluggiat.sandbox

/**
 * Outcome of [PluginSandboxStrategy.activate] for one loaded plugin - analogous to
 * [org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult] for the pre-load security
 * chain.
 */
sealed interface SandboxCheckResult {

    /** Sandbox activation succeeded for the plugin. */
    data object Success : SandboxCheckResult

    /**
     * Sandbox activation failed for the plugin.
     *
     * @property reason human-readable reason for the failure
     */
    data class Failure(val reason: String) : SandboxCheckResult
}
