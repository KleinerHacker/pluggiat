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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThreadWatchdogTest {

    private fun policyWithTimeout(millis: Long): PluginSandboxPolicy =
        PluginSandboxPolicy(callTimeout = Duration.ofMillis(millis))

    /**
     * Use case: a call that completes well within a configured timeout returns the block's value
     * unchanged.
     */
    @Test
    fun `fast call under a timeout completes normally`() {
        val watchdog = ThreadWatchdog()

        val result = watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { 42 }

        assertEquals(42, result)
    }

    /**
     * Use case: a call exceeding its configured timeout throws [SandboxTimeoutException] and reports
     * exactly one category-less [SandboxViolation] via [ThreadWatchdog.onViolation].
     */
    @Test
    fun `call exceeding the timeout throws SandboxTimeoutException and reports a violation`() {
        val watchdog = ThreadWatchdog()
        var violationCount = 0
        var lastViolation: SandboxViolation? = null
        watchdog.onViolation = { _, violation -> violationCount++; lastViolation = violation }
        val startedLatch = CountDownLatch(1)

        assertThrows(SandboxTimeoutException::class.java) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(50)) {
                startedLatch.countDown()
                Thread.sleep(5000)
            }
        }
        startedLatch.await(1, TimeUnit.SECONDS)

        assertEquals(1, violationCount)
        assertEquals("plugin-a", lastViolation?.pluginId)
        assertNull(lastViolation?.category)
    }

    /**
     * Use case: a policy without a configured timeout runs the block directly on the calling thread -
     * the zero-overhead path for the fully permissive default policy.
     */
    @Test
    fun `policy without a timeout runs on the calling thread`() {
        val watchdog = ThreadWatchdog()
        val callingThread = Thread.currentThread()
        var threadInsideBlock: Thread? = null

        watchdog.runGoverned("plugin-a", PluginSandboxPolicy.UNRESTRICTED) {
            threadInsideBlock = Thread.currentThread()
        }

        assertSame(callingThread, threadInsideBlock)
    }

    /**
     * Use case: after one call for a plugin has timed out, a later call for the *same* plugin still
     * succeeds - the abandoned executor was replaced, not left permanently wedged.
     */
    @Test
    fun `a later call for the same plugin still succeeds after a previous timeout`() {
        val watchdog = ThreadWatchdog()

        assertThrows(SandboxTimeoutException::class.java) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(50)) { Thread.sleep(5000) }
        }
        val result = watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "recovered" }

        assertEquals("recovered", result)
    }

    /**
     * Use case: after [ThreadWatchdog.deactivate], a further governed call for the same plugin id
     * fails fast with [SandboxDeactivatedException] instead of silently creating a fresh executor for
     * code that should no longer be running.
     */
    @Test
    fun `a call after deactivate fails fast instead of creating a fresh executor`() {
        val watchdog = ThreadWatchdog()
        watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "first" }

        watchdog.deactivate("plugin-a")

        assertThrows(SandboxDeactivatedException::class.java) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "second" }
        }
    }

    /**
     * Use case: [ThreadWatchdog.activate] clears a previous [ThreadWatchdog.deactivate] marker, so a
     * freshly (re)loaded plugin of the same id is governed normally again.
     */
    @Test
    fun `activate clears a previous deactivate marker`() {
        val watchdog = ThreadWatchdog()
        watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "first" }
        watchdog.deactivate("plugin-a")

        watchdog.activate("plugin-a")
        val result = watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "second" }

        assertEquals("second", result)
    }

    /**
     * Use case: a reentrant governed call for the same plugin id (from within an already-governed
     * call for that plugin) runs directly instead of being submitted to the single-thread executor
     * again, which would otherwise deadlock the outer call against itself.
     */
    @Test
    fun `a reentrant call for the same plugin does not deadlock`() {
        val watchdog = ThreadWatchdog()

        val result = watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { "inner" }
        }

        assertEquals("inner", result)
    }

    /**
     * Use case: when a governed call times out while a second governed call for the *same* plugin is
     * still queued behind it on the single-thread executor, abandoning (`shutdownNow`) that executor
     * finds pending work and takes the "had N pending sandbox-governed call(s) still queued at
     * shutdown" warning path instead of the empty-queue path.
     */
    @Test
    fun `a timeout with a call still queued behind it hits the pending-work shutdown path`() {
        val watchdog = ThreadWatchdog()
        val firstStartedLatch = CountDownLatch(1)

        val secondCallThread = Thread {
            runCatching {
                watchdog.runGoverned("plugin-a", policyWithTimeout(5000)) { "queued" }
            }
        }

        assertThrows(SandboxTimeoutException::class.java) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(50)) {
                firstStartedLatch.countDown()
                // Give the second call a moment to actually queue behind this one before we overrun our timeout.
                secondCallThread.start()
                Thread.sleep(200)
                Thread.sleep(5000)
            }
        }
        secondCallThread.join(5000)
    }

    /**
     * Use case: concurrent governed calls for the same plugin id are serialized on that plugin's
     * single-thread executor rather than running in parallel.
     */
    @Test
    fun `concurrent calls for the same plugin are serialized`() {
        val watchdog = ThreadWatchdog()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        val firstCallThread = Thread {
            watchdog.runGoverned("plugin-a", policyWithTimeout(5000)) {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        }
        firstCallThread.start()
        firstStarted.await(1, TimeUnit.SECONDS)

        val secondCallThread = Thread {
            watchdog.runGoverned("plugin-a", policyWithTimeout(5000)) {
                secondStarted.countDown()
            }
        }
        secondCallThread.start()

        val secondStartedBeforeReleasingFirst = secondStarted.await(200, TimeUnit.MILLISECONDS)
        releaseFirst.countDown()
        firstCallThread.join(5000)
        secondCallThread.join(5000)

        assertEquals(false, secondStartedBeforeReleasingFirst)
    }

    /**
     * Use case: an exception thrown by the governed block on the watchdog thread is unwrapped from
     * its `ExecutionException` and rethrown to the caller as the original exception.
     */
    @Test
    fun `an exception thrown by the block is rethrown unwrapped to the caller`() {
        val watchdog = ThreadWatchdog()

        val thrown = assertThrows(IllegalStateException::class.java) {
            watchdog.runGoverned<Unit>("plugin-a", policyWithTimeout(1000)) { throw IllegalStateException("plugin failure") }
        }

        assertEquals("plugin failure", thrown.message)
    }

    /**
     * Use case: the calling thread is interrupted while it waits for a governed call - the call is
     * cancelled, the interrupt flag is restored and the [InterruptedException] propagates.
     */
    @Test
    fun `an interrupted caller cancels the call and keeps its interrupt flag`() {
        val watchdog = ThreadWatchdog()
        val caught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val flagRestored = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = CountDownLatch(1)
        val caller = Thread {
            try {
                watchdog.runGoverned("plugin-a", policyWithTimeout(10_000)) {
                    started.countDown()
                    Thread.sleep(5000)
                }
            } catch (e: InterruptedException) {
                caught.set(e)
                flagRestored.set(Thread.currentThread().isInterrupted)
            }
        }

        caller.start()
        started.await(2, TimeUnit.SECONDS)
        caller.interrupt()
        caller.join(5000)

        assertEquals(InterruptedException::class.java, caught.get()?.javaClass)
        assertEquals(true, flagRestored.get())
    }

    /**
     * Use case: replacing a timed-out executor for a plugin whose slot was concurrently marked
     * deactivated leaves the deactivated marker untouched - a later call is still refused.
     */
    @Test
    fun `replacing an executor keeps a concurrent deactivation marker`() {
        val watchdog = ThreadWatchdog()
        watchdog.deactivate("plugin-a")
        val staleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val replace = ThreadWatchdog::class.java.getDeclaredMethod(
            "replaceExecutor", String::class.java, java.util.concurrent.ExecutorService::class.java,
        ).apply { isAccessible = true }

        replace.invoke(watchdog, "plugin-a", staleExecutor)

        assertEquals(true, staleExecutor.isShutdown)
        assertThrows(SandboxDeactivatedException::class.java) {
            watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { 1 }
        }
    }

    /**
     * Use case: replacing a timed-out executor whose slot already holds a different, newer executor
     * leaves that newer executor in place - a later governed call still succeeds.
     */
    @Test
    fun `replacing a stale executor keeps a newer live executor`() {
        val watchdog = ThreadWatchdog()
        assertEquals(1, watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { 1 })
        val staleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val replace = ThreadWatchdog::class.java.getDeclaredMethod(
            "replaceExecutor", String::class.java, java.util.concurrent.ExecutorService::class.java,
        ).apply { isAccessible = true }

        replace.invoke(watchdog, "plugin-a", staleExecutor)

        assertEquals(true, staleExecutor.isShutdown)
        assertEquals(2, watchdog.runGoverned("plugin-a", policyWithTimeout(1000)) { 2 })
    }

    /**
     * Use case: an uncaught exception on a watchdog thread is logged by the thread's uncaught
     * exception handler; the thread is a daemon and terminates without affecting the host.
     */
    @Test
    fun `an uncaught exception on a watchdog thread is handled and the thread ends`() {
        val watchdog = ThreadWatchdog()
        val newExecutor = ThreadWatchdog::class.java.getDeclaredMethod("newExecutorFor", String::class.java)
            .apply { isAccessible = true }
        val executor = newExecutor.invoke(watchdog, "plugin-a") as java.util.concurrent.ExecutorService
        val threadRef = java.util.concurrent.atomic.AtomicReference<Thread>()

        executor.execute {
            threadRef.set(Thread.currentThread())
            throw IllegalStateException("uncaught in watchdog thread")
        }
        executor.shutdown()

        assertEquals(true, executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(true, threadRef.get().isDaemon)
    }
}
