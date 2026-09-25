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

import java.time.Duration

/**
 * A risk-bearing JDK API category a [PluginSandboxPolicy] can restrict access to.
 *
 * Purely descriptive as of IP-01 - no [PluginSandboxStrategy] shipped with this implementation plan
 * actually inspects [PluginSandboxPolicy.allowedApiCategories] yet; the concrete mediation is added
 * by IP-02.
 */
enum class SandboxApiCategory {
    /** File system access outside of an allowed root directory. */
    FILESYSTEM,

    /** Network access (sockets, HTTP clients, etc.). */
    NETWORK,

    /** Reflection onto host or foreign-plugin-internal classes. */
    REFLECTION,

    /** Starting an external OS process. */
    PROCESS_START,

    /** Creating a new JVM thread. */
    THREAD_CREATION,
}

/**
 * How strictly a plugin is isolated from the host process.
 */
enum class SandboxIsolationLevel {
    /** The plugin runs in the host's own JVM process, mediated in-process (see IP-02/IP-03). */
    IN_VM,

    /** The plugin runs in a separate JVM subprocess (see IP-04). */
    PROCESS,
}

/**
 * A runtime sandbox configuration, set per [org.pcsoft.framework.pluggiat.scanner.PluginLocation]
 * (override) or per [org.pcsoft.framework.pluggiat.scanner.PluginLocationType] (default) - analogous
 * to the security fallback chain configured via
 * [org.pcsoft.framework.pluggiat.PluginLocationBuilder.securityOverride]/
 * [org.pcsoft.framework.pluggiat.PluginManagerConfiguration.defaultSecurityChain].
 *
 * As of IP-01, this is purely a configuration holder: no [PluginSandboxStrategy] shipped with this
 * implementation plan enforces any of these fields yet. It becomes effective once IP-02 (API
 * mediation), IP-03 (thread/time-limit governance) and IP-04 (process isolation) are implemented.
 *
 * @property allowedApiCategories [SandboxApiCategory] values a plugin under this policy may use
 * directly; defaults to all categories (fully permissive)
 * @property callTimeout maximum duration a single lifecycle hook or extension call may run before
 * being treated as a violation; `null` means unbounded
 * @property isolationLevel the isolation level enforced for a plugin under this policy
 */
data class PluginSandboxPolicy(
    val allowedApiCategories: Set<SandboxApiCategory> = SandboxApiCategory.entries.toSet(),
    val callTimeout: Duration? = null,
    val isolationLevel: SandboxIsolationLevel = SandboxIsolationLevel.IN_VM,
) {
    companion object {
        /** The fully permissive default policy applied when no override/default is configured. */
        val UNRESTRICTED: PluginSandboxPolicy = PluginSandboxPolicy()
    }
}
