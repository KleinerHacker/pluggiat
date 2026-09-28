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
import org.slf4j.LoggerFactory
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
 * Deactivation is *fail-closed*: [revoke] does not drop the class loader's registration but replaces
 * it with a [State.Revoked] marker, under which [check] blocks every category unconditionally. A
 * plugin thread that survives its plugin's unload (see
 * [org.pcsoft.framework.pluggiat.sandbox.ThreadWatchdog]) would otherwise find no entry at all for
 * its class loader and - under the previous fail-open behaviour - be granted every guarded API it
 * asks for, which is precisely the state an attacking plugin wants to reach. The marker is retained
 * deliberately: it stays as long as the revoked class loader itself does, which is exactly as long as
 * one of its classes can still execute a guard call. [release] is the explicit way to drop it once
 * the class loader is definitively discarded and no code of it can run any more.
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
    private val logger = LoggerFactory.getLogger(SandboxGuardRegistry::class.java)

    /** What is currently known about one plugin class loader. */
    private sealed interface State {
        val pluginId: String
        val onViolation: (String, SandboxViolation) -> Unit

        /** An active registration: [policy] decides which categories are allowed. */
        data class Entry(
            override val pluginId: String,
            val policy: PluginSandboxPolicy,
            override val onViolation: (String, SandboxViolation) -> Unit,
        ) : State

        /** A revoked registration: every category is blocked, no matter what the policy once allowed. */
        data class Revoked(
            override val pluginId: String,
            override val onViolation: (String, SandboxViolation) -> Unit,
        ) : State
    }

    private val states = ConcurrentHashMap<ClassLoader, State>()

    /**
     * Registers [policy] for [pluginId], effective for every class loaded by [classLoader].
     * [onViolation] is invoked (before [check] throws) whenever a guarded call is blocked - wired by
     * [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy] to
     * [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.reportViolation].
     *
     * Re-registering the same [classLoader] overwrites a previous registration, including a
     * [State.Revoked] marker: a class loader only ever gets re-registered by a successful
     * (re)activation of its plugin, which is the one legitimate way out of the revoked state.
     */
    fun register(classLoader: ClassLoader, pluginId: String, policy: PluginSandboxPolicy, onViolation: (String, SandboxViolation) -> Unit) {
        // SECURITY: keyed by the plugin's class loader, which is the only thing a guard call can derive from
        // SECURITY: its caller - a plugin cannot present a different identity than the loader that defined it.
        logger.trace("Registering sandbox guard policy for plugin '{}': allowedApiCategories={}", pluginId, policy.allowedApiCategories)
        states[classLoader] = State.Entry(pluginId, policy, onViolation)
    }

    /**
     * Marks [classLoader] revoked, called when its plugin is deactivated/unloaded: from now on
     * [check] blocks every guarded call made from one of its classes, instead of falling back to
     * "not a plugin class, nothing to enforce". A class loader that was never registered stays
     * unknown - there is no policy to enforce for it, and inventing an entry would retain a class
     * loader the framework does not manage.
     */
    fun revoke(classLoader: ClassLoader) {
        // SECURITY: replaced, not removed: the marker is what makes a later guard call from a surviving plugin
        // SECURITY: thread fail instead of finding no policy and being waved through (fail-closed).
        // SECURITY: computeIfPresent, so an unknown loader is not invented into the map - only loaders the
        // SECURITY: framework itself registered are tracked.
        states.computeIfPresent(classLoader) { _, state ->
            logger.trace("Revoking sandbox guard registration for plugin '{}' (fail-closed from now on)", state.pluginId)
            State.Revoked(state.pluginId, state.onViolation)
        }
    }

    /**
     * Removes any registration for [classLoader] for good, giving up the fail-closed guarantee for
     * it. Only legitimate once the class loader is definitively discarded and none of its classes can
     * execute another guard call.
     */
    fun release(classLoader: ClassLoader) {
        // SECURITY: gives up the fail-closed guarantee for this loader - only ever correct once none of its
        // SECURITY: classes can run again.
        val removed = states.remove(classLoader)
        if (removed != null) logger.trace("Released sandbox guard registration for plugin '{}'", removed.pluginId)
    }

    /**
     * Called by instrumented plugin bytecode right before a guarded call. A no-op if [callerType]'s
     * class loader has no registration at all (not a plugin class) or if [category] is allowed by its
     * registered policy.
     *
     * @throws SandboxViolationException if [category] is not allowed by the registered policy, or if
     * the class loader's registration was [revoke]d - aborts the guarded call before it executes
     */
    @JvmStatic
    fun check(callerType: Class<*>, category: SandboxApiCategory) {
        // SECURITY: the caller's own class loader decides which policy applies; a class of an unregistered
        // SECURITY: loader is not a plugin class and has nothing to enforce.
        val state = states[callerType.classLoader] ?: return
        val reason = when (state) {
            is State.Entry -> {
                // SECURITY: allow-list semantics - a category has to be listed explicitly to pass, so a new,
                // SECURITY: unclassified category is denied rather than permitted by default.
                if (category in state.policy.allowedApiCategories) {
                    // Guarded by isTraceEnabled: this runs on every guarded call site in instrumented plugin
                    // bytecode, so the varargs/formatting cost must not be paid when TRACE is disabled.
                    if (logger.isTraceEnabled) {
                        logger.trace("Allowed {} access from {} for plugin '{}' (allow-listed)", category, callerType.name, state.pluginId)
                    }
                    return
                }
                "Blocked $category access attempted from ${callerType.name}"
            }

            // SECURITY: revoked means every category is blocked, whatever the policy once allowed.
            is State.Revoked ->
                "Blocked $category access attempted from ${callerType.name} after its plugin was deactivated"
        }

        val violation = SandboxViolation(
            pluginId = state.pluginId,
            category = category,
            reason = reason,
        )
        logger.trace("Denied {} access from {} for plugin '{}': {}", category, callerType.name, state.pluginId, reason)
        // SECURITY: the host is notified first, then the call is aborted - the notification must not depend
        // SECURITY: on anyone catching the exception.
        state.onViolation(state.pluginId, violation)
        // SECURITY: thrown *before* the guarded instruction executes, so the blocked call never happens.
        throw SandboxViolationException(violation)
    }
}
