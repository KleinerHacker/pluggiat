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
 * A single detected breach of a [PluginSandboxPolicy] by a loaded plugin, reported via
 * [PluginSandbox.reportViolation] - analogous to
 * [org.pcsoft.framework.pluggiat.security.PluginSecurityCheckResult.Failure] for the pre-load
 * security chain.
 *
 * @property pluginId id of the plugin that caused the violation
 * @property category the [SandboxApiCategory] the violation belongs to, `null` if not attributable
 * to a single category (e.g. a timeout)
 * @property reason human-readable description of the violation
 */
data class SandboxViolation(
    val pluginId: String,
    val category: SandboxApiCategory?,
    val reason: String,
)
