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

package org.pcsoft.framework.pluggiat.sandbox.agent

import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation

/**
 * Thrown by [SandboxGuardRegistry.check] to abort a guarded call a plugin's
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy] does not allow - deliberately a
 * `RuntimeException`, not a checked exception, since it is thrown from bytecode injected in front of
 * a call the plugin's own code never declared as throwing it.
 */
class SandboxViolationException(val violation: SandboxViolation) :
    RuntimeException("Sandbox violation for plugin '${violation.pluginId}': ${violation.reason}")
