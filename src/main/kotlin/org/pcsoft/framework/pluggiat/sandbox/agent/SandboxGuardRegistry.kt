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

import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-wide registry mapping a loaded plugin's [ClassLoader] to its currently active
 * [PluginSandboxPolicy] - the runtime counterpart to [GuardAsmVisitorWrapper]'s instrumentation,
 * consulted by every guard call it injects.
 *
 * A guard call only ever knows the [Class] executing it (baked into the instrumented bytecode as a
 * constant of the class being transformed, see [GuardAsmVisitorWrapper]); [check] resolves that
 * class's own [Class.getClassLoader] back to the plugin it belongs to.
 *
 * `internal`: not part of the public API. A host (or any other part of the framework) must go
 * through [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox] instead, exactly as for a
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxStrategy] implementation - `internal` blocks
 * that for any other Kotlin module at compile time. Its members are deliberately left without their
 * own visibility modifier (plain, implicitly public) rather than also marked `internal`: [check] is
 * invoked via a plain, hardcoded `INVOKESTATIC` that [GuardAsmVisitorWrapper] emits directly into
 * arbitrary plugin bytecode, referencing this class and method by their exact, unmangled names -
 * unlike a class, an `internal` *function* additionally gets its name mangled by the compiler (e.g.
 * `check$pluggiat_main`), which would silently break every already-instrumented call site. The JVM
 * itself has no notion of Kotlin's module-private visibility either way - both the class and its
 * members always end up `public` in the actual bytecode, which is exactly what the injected call
 * needs; `internal` only stops other *Kotlin* source from referencing this object directly.
 */
internal object SandboxGuardRegistry {
    private data class Entry(
        val pluginId: String,
        val policy: PluginSandboxPolicy,
        val onViolation: (String, SandboxViolation) -> Unit,
    )

    private val entries = ConcurrentHashMap<ClassLoader, Entry>()

    /**
     * Registers [policy] for [pluginId], effective for every class loaded by [classLoader].
     * [onViolation] is invoked (before [check] throws) whenever a guarded call is blocked - wired by
     * [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy] to
     * [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.reportViolation].
     */
    fun register(classLoader: ClassLoader, pluginId: String, policy: PluginSandboxPolicy, onViolation: (String, SandboxViolation) -> Unit) {
        entries[classLoader] = Entry(pluginId, policy, onViolation)
    }

    /**
     * Removes any registration for [classLoader], called when its plugin is deactivated/unloaded.
     */
    fun unregister(classLoader: ClassLoader) {
        entries.remove(classLoader)
    }

    /**
     * Called by instrumented plugin bytecode right before a guarded call. A no-op if [callerType]'s
     * class loader has no registered policy (not a plugin class, or its plugin was already
     * deactivated) or if [category] is allowed by the registered policy.
     *
     * @throws SandboxViolationException if [category] is not allowed by the registered policy -
     * aborts the guarded call before it executes
     */
    @JvmStatic
    fun check(callerType: Class<*>, category: SandboxApiCategory) {
        val entry = entries[callerType.classLoader] ?: return
        if (category in entry.policy.allowedApiCategories) return

        val violation = SandboxViolation(
            pluginId = entry.pluginId,
            category = category,
            reason = "Blocked $category access attempted from ${callerType.name}",
        )
        entry.onViolation(entry.pluginId, violation)
        throw SandboxViolationException(violation)
    }
}
