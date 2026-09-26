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

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * IP-03's thread/time-limit governance behind [PluginSandbox.runGoverned]/[PluginSandbox.activate]/
 * [PluginSandbox.deactivate] - a dedicated collaborator of [PluginSandbox], not a
 * [PluginSandboxStrategy]: it is orthogonal to [AgentInstrumentationStrategy]'s bytecode API
 * mediation, and [PluginSandbox] already implements these methods itself rather than delegating
 * them through that single-method interface.
 *
 * A call for a plugin id whose [PluginSandboxPolicy.callTimeout] is `null` runs directly on the
 * calling thread, at zero governance overhead. Otherwise it runs on that plugin's own, single-thread
 * [ExecutorService] (created lazily on first use, one worker thread per plugin id) tracked as a
 * [Slot], so concurrent calls for the same plugin are serialized rather than spawning additional
 * threads. A *reentrant* call for the same plugin id (from within an already-governed call, e.g. a
 * host callback invoked synchronously from a lifecycle hook) runs directly instead of being submitted
 * again - submitting it would deadlock the single worker thread against itself.
 *
 * A timed-out call's worker thread is *not* forcibly stopped - `Thread.stop()` is unsafe, and
 * Kotlin's synchronous `block: () -> T` gives no general cancellation guarantee. It is interrupted
 * best-effort via [java.util.concurrent.Future.cancel] and then abandoned; its executor is shut down
 * immediately (`shutdownNow()`, so it stops accepting further work right away) and replaced so a
 * later call for the same plugin does not queue behind the orphaned task. Being a daemon thread, an
 * abandoned worker can never prevent JVM shutdown on its own.
 *
 * [deactivate] marks a plugin id as deactivated rather than merely forgetting it: a [runGoverned]
 * call racing against a concurrent [deactivate] for the same plugin id either completes against the
 * about-to-be-shut-down executor or observes the marker and fails fast with
 * [SandboxDeactivatedException] - it never silently creates a fresh executor for a plugin that was
 * just deactivated (which would let its code keep running after being unloaded, e.g. after a
 * category-attributed sandbox violation). [activate] clears the marker again for a freshly (re)loaded
 * plugin of the same id.
 *
 * @property onViolation invoked with a [SandboxViolation] whenever a call times out; set by
 * [PluginSandbox]'s `init` block to its own [PluginSandbox.reportViolation] right after construction
 * (a constructor parameter cannot reference an outer `this` that is not yet fully constructed) - a
 * no-op until then
 */
class ThreadWatchdog {
    var onViolation: (pluginId: String, violation: SandboxViolation) -> Unit = { _, _ -> }

    private val logger = LoggerFactory.getLogger(ThreadWatchdog::class.java)
    private val rootGroup = ThreadGroup("pluggiat-sandbox")
    private val slots = ConcurrentHashMap<String, Slot>()
    private val currentPluginId = ThreadLocal<String?>()

    /** Per-plugin-id governance state held in [slots]. */
    private sealed interface Slot {
        /** A live, usable executor for this plugin id. */
        data class Ready(val executor: ExecutorService) : Slot

        /** [deactivate] was called for this plugin id; no new executor may be created until [activate]. */
        data object Deactivated : Slot
    }

    /**
     * Clears a previous [deactivate] marker for [pluginId], if any - called by [PluginSandbox.activate]
     * right after a plugin (re)loaded, so it is governed normally again instead of being permanently
     * rejected by a stale marker from an earlier unload.
     */
    fun activate(pluginId: String) {
        slots.remove(pluginId)
    }

    /**
     * Runs [block] for [pluginId] under [policy]. Directly on the calling thread if
     * [PluginSandboxPolicy.callTimeout] is `null` or the call is reentrant for [pluginId]; otherwise
     * on [pluginId]'s dedicated executor, bounded by that timeout.
     *
     * @throws SandboxTimeoutException if [block] does not complete within the configured timeout
     * @throws SandboxDeactivatedException if [pluginId] was [deactivate]d and not [activate]d since
     */
    fun <T> runGoverned(pluginId: String, policy: PluginSandboxPolicy, block: () -> T): T {
        val timeout = policy.callTimeout ?: return block()
        if (currentPluginId.get() == pluginId) return block()

        val slot = slots.compute(pluginId) { _, existing ->
            when (existing) {
                is Slot.Ready -> existing
                Slot.Deactivated -> existing
                null -> Slot.Ready(newExecutorFor(pluginId))
            }
        }
        if (slot !is Slot.Ready) throw SandboxDeactivatedException(pluginId)
        val executor = slot.executor

        val future = executor.submit(
            Callable {
                val previous = currentPluginId.get()
                currentPluginId.set(pluginId)
                try {
                    block()
                } finally {
                    currentPluginId.set(previous)
                }
            },
        )
        return try {
            future.get(timeoutMillis(timeout), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            replaceExecutor(pluginId, executor)
            onViolation(pluginId, SandboxViolation(pluginId, category = null, reason = "Call exceeded sandbox timeout of $timeout"))
            throw SandboxTimeoutException(pluginId, timeout)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Marks [pluginId] as deactivated (see [Slot.Deactivated]) and, if it had a live executor, shuts
     * it down immediately - called by [PluginSandbox.deactivate] as part of unloading/reloading a
     * plugin.
     */
    fun deactivate(pluginId: String) {
        val previous = slots.put(pluginId, Slot.Deactivated)
        if (previous is Slot.Ready) shutdownNow(pluginId, previous.executor)
    }

    /**
     * Replaces [pluginId]'s slot with an empty one, but only if it still holds exactly
     * [timedOutExecutor] - a concurrent [deactivate] (which sets [Slot.Deactivated]) or an already
     * different replacement always wins and is left untouched. Either way, [timedOutExecutor] itself
     * is shut down immediately.
     */
    private fun replaceExecutor(pluginId: String, timedOutExecutor: ExecutorService) {
        slots.compute(pluginId) { _, existing ->
            if (existing is Slot.Ready && existing.executor === timedOutExecutor) null else existing
        }
        shutdownNow(pluginId, timedOutExecutor)
    }

    private fun shutdownNow(pluginId: String, executor: ExecutorService) {
        val pending = executor.shutdownNow()
        if (pending.isNotEmpty()) {
            logger.warn("Plugin '{}' had {} pending sandbox-governed call(s) still queued at shutdown", pluginId, pending.size)
        }
    }

    /** [Duration.toMillis] rounds a sub-millisecond timeout down to `0`, which `Future.get` treats as "no wait at all" - floor it at 1ms instead. */
    private fun timeoutMillis(timeout: Duration): Long = timeout.toMillis().coerceAtLeast(1)

    private fun newExecutorFor(pluginId: String): ExecutorService {
        val safeId = sanitizeForThreadName(pluginId)
        val group = ThreadGroup(rootGroup, "pluggiat-plugin-$safeId")
        val threadFactory = ThreadFactory { runnable ->
            Thread(group, runnable, "pluggiat-watchdog-$safeId").apply {
                isDaemon = true
                setUncaughtExceptionHandler { thread, throwable ->
                    logger.warn("Uncaught exception on sandbox watchdog thread '{}'", thread.name, throwable)
                }
            }
        }
        return Executors.newSingleThreadExecutor(threadFactory)
    }

    companion object {
        private val UNSAFE_THREAD_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
        private const val MAX_THREAD_NAME_ID_LENGTH = 64

        /**
         * A plugin id comes unvalidated from its manifest and could contain control characters,
         * newlines or be arbitrarily long; sanitized before use in a thread/`ThreadGroup` name so it
         * cannot corrupt thread-dump or monitoring output.
         */
        private fun sanitizeForThreadName(pluginId: String): String =
            UNSAFE_THREAD_NAME_CHARS.replace(pluginId, "_").take(MAX_THREAD_NAME_ID_LENGTH)
    }
}
