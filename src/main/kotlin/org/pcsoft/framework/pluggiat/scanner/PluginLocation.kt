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

package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.classloader.PluginDependencyStrategy
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy
import java.nio.file.Path

/**
 * A single configured location plugins are scanned from.
 *
 * @property path directory to scan
 * @property type whether this location is shipped with the host application or contributed externally
 * @property scanStrategy the load mode of this location, expressed as the concrete scan strategy to use
 * @property securityOverride ordered fallback chain of security strategies overriding the default
 * chain configured for this location's [type]; empty means the type's default chain applies. If
 * that is empty too (the built-in default), scanning this location fails with a configuration
 * error - some security chain must always be configured, explicitly including
 * `org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy` if no check is desired (see
 * `org.pcsoft.framework.pluggiat.security.PluginSecurity`)
 * @property dependencyStrategyOverride [PluginDependencyStrategy] overriding the global default
 * dependency-visibility strategy for this location; `null` means the global default applies. A
 * location can always see plugins within itself, regardless of the effective strategy, unless that
 * strategy is `org.pcsoft.framework.pluggiat.classloader.DisallowPluginDependencyStrategy`.
 * @property sandboxOverride [PluginSandboxPolicy] overriding the default policy configured for this
 * location's [type]; `null` means the type's default policy applies (or
 * [PluginSandboxPolicy.UNRESTRICTED] if that is unconfigured too) - unlike [securityOverride], an
 * unconfigured sandbox never fails a load, see `org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.effectivePolicy`
 */
data class PluginLocation(
    val path: Path,
    val type: PluginLocationType,
    val scanStrategy: PluginScanStrategy = ZipJarScanStrategy(),
    val securityOverride: List<PluginSecurityStrategy> = emptyList(),
    val dependencyStrategyOverride: PluginDependencyStrategy? = null,
    val sandboxOverride: PluginSandboxPolicy? = null,
)
