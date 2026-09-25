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

import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin

/**
 * A concrete runtime sandbox mechanism, used exclusively internally by [PluginSandbox] - never
 * addressed directly by [org.pcsoft.framework.pluggiat.PluginManager] or a host, analogous to how
 * [org.pcsoft.framework.pluggiat.security.PluginSecurityStrategy] is only reachable through
 * [org.pcsoft.framework.pluggiat.security.PluginSecurity].
 *
 * IP-01 ships only [NoOpSandboxStrategy]; concrete implementations (bytecode API mediation, thread
 * watchdog, process isolation) are added by IP-02/IP-03/IP-04.
 */
interface PluginSandboxStrategy {

    /**
     * Activates this strategy for [loadedPlugin] under [policy], called by [PluginSandbox.activate]
     * right after the plugin was loaded, before its extensions are activated.
     */
    fun activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult
}
