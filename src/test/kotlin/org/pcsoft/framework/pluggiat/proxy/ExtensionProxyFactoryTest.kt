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

package org.pcsoft.framework.pluggiat.proxy

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.exception.DefaultExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingAction
import org.pcsoft.framework.pluggiat.exception.ExceptionHandlingStrategy
import org.pcsoft.framework.pluggiat.exception.PluginExecutionException
import org.pcsoft.framework.pluggiat.exception.PluginFatalException
import org.pcsoft.framework.pluggiat.sandbox.PluginSandbox
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException
import java.time.Duration

interface Greeter {
    fun greet(name: String): String
    fun fail(): String
    fun makeFactory(): Greeter
    fun greeters(): List<Greeter>
    fun greetersByLabel(): Map<String, Greeter>
    fun greeterArray(): Array<Greeter>
    fun asText(): String
    fun asFinal(): FinalPayload
    fun hang(): String
}

class FinalPayload(val value: String)

class FailingGreeter(private val throwable: Throwable) : Greeter {
    override fun greet(name: String): String = throw throwable
    override fun fail(): String = throw throwable
    override fun makeFactory(): Greeter = throw throwable
    override fun greeters(): List<Greeter> = throw throwable
    override fun greetersByLabel(): Map<String, Greeter> = throw throwable
    override fun greeterArray(): Array<Greeter> = throw throwable
    override fun asText(): String = throw throwable
    override fun asFinal(): FinalPayload = throw throwable
    override fun hang(): String = throw throwable
}

open class OpenGreeterImpl : Greeter {
    override fun greet(name: String): String = "Hello, $name!"
    override fun fail(): String = throw IllegalStateException("boom")
    override fun makeFactory(): Greeter = OpenGreeterImpl()
    override fun greeters(): List<Greeter> = listOf(OpenGreeterImpl())
    override fun greetersByLabel(): Map<String, Greeter> = mapOf("a" to OpenGreeterImpl())
    override fun greeterArray(): Array<Greeter> = arrayOf(OpenGreeterImpl())
    override fun asText(): String = "plain"
    override fun asFinal(): FinalPayload = FinalPayload("payload")
    override fun hang(): String {
        Thread.sleep(5000)
        return "too late"
    }
}

class FinalGreeterImpl : Greeter {
    override fun greet(name: String): String = "Hi, $name!"
    override fun fail(): String = throw IllegalStateException("boom")
    override fun makeFactory(): Greeter = FinalGreeterImpl()
    override fun greeters(): List<Greeter> = emptyList()
    override fun greetersByLabel(): Map<String, Greeter> = emptyMap()
    override fun greeterArray(): Array<Greeter> = emptyArray()
    override fun asText(): String = "plain"
    override fun asFinal(): FinalPayload = FinalPayload("payload")
    override fun hang(): String = "n/a"
}

class ExtensionProxyFactoryTest {
    private val strategy: ExceptionHandlingStrategy = DefaultExceptionHandlingStrategy()

    /**
     * Use case: a proxy over an interface delegates a successful call to the real instance
     * unchanged.
     */
    @Test
    fun `interface proxy delegates a successful call to the real instance`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        assertEquals("Hello, World!", proxy.greet("World"))
    }

    /**
     * Use case: an unchecked exception escaping the real instance is caught and re-thrown as
     * [PluginFatalException] per the default matrix (IGNORE/UNLOAD/CRASH resolution).
     */
    @Test
    fun `interface proxy converts an unchecked exception into PluginFatalException`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        assertThrows(PluginFatalException::class.java) { proxy.fail() }
    }

    /**
     * Use case: a [PluginExecutionException]-recommended checked failure resolves to IGNORE and is
     * re-thrown as [PluginExecutionException], leaving the plugin active.
     */
    @Test
    fun `checked PluginExecutionException resolves to IGNORE`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, FailingGreeter(PluginExecutionException("nope")), strategy) {}

        assertThrows(PluginExecutionException::class.java) { proxy.fail() }
    }

    /**
     * Use case: an open (non-final) Kotlin class is proxied via the ByteBuddy path and behaves
     * identically to the interface-based JDK proxy for a successful call.
     */
    @Test
    fun `open class proxy via ByteBuddy behaves like the interface proxy`() {
        val proxy = ExtensionProxyFactory.create(OpenGreeterImpl::class.java, OpenGreeterImpl(), strategy) {}

        assertEquals("Hello, World!", proxy.greet("World"))
    }

    /**
     * Use case: an open class proxy also converts an escaping exception the same way as the
     * interface proxy.
     */
    @Test
    fun `open class proxy converts an unchecked exception into PluginFatalException`() {
        val proxy = ExtensionProxyFactory.create(OpenGreeterImpl::class.java, OpenGreeterImpl(), strategy) {}

        assertThrows(PluginFatalException::class.java) { proxy.fail() }
    }

    /**
     * Use case: a final class is rejected as not proxy-eligible.
     */
    @Test
    fun `final class is rejected as not proxy-eligible`() {
        assertFalse(isProxyEligible(FinalGreeterImpl::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionProxyFactory.create(FinalGreeterImpl::class.java, FinalGreeterImpl(), strategy) {}
        }
    }

    /**
     * Use case: UNLOAD is executed synchronously inside the proxy call, before the resulting
     * PluginFatalException is thrown to the caller.
     */
    @Test
    fun `UNLOAD callback runs synchronously before PluginFatalException is thrown`() {
        var unloadedBeforeException = false
        val proxy = ExtensionProxyFactory.create(
            Greeter::class.java,
            OpenGreeterImpl(),
            strategy,
        ) { unloadedBeforeException = true }

        val thrown = assertThrows(PluginFatalException::class.java) { proxy.fail() }
        assertTrue(unloadedBeforeException, "onUnload must have run before the exception was thrown")
        assertTrue(thrown.cause is IllegalStateException)
    }

    /**
     * Use case: a factory-style method's return value is recursively wrapped in the same proxy
     * mechanism, so calls on the returned instance are enforced as well.
     */
    @Test
    fun `factory return value is recursively wrapped and enforced`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        val nested = proxy.makeFactory()

        assertThrows(PluginFatalException::class.java) { nested.fail() }
    }

    /**
     * Use case: `String` return values pass through unchanged, without going through the proxy
     * mechanism.
     */
    @Test
    fun `String return values pass through unchanged`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        assertEquals("plain", proxy.asText())
    }

    /**
     * Use case: an own (non-standard) final class returned from a method is passed through
     * unchanged - the security guarantee does not apply to it, but the value itself is preserved.
     */
    @Test
    fun `own final class return value is passed through unchanged`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        val payload = proxy.asFinal()

        assertEquals("payload", payload.value)
    }

    /**
     * Use case: `List<Greeter>` return values are wrapped element-wise.
     */
    @Test
    fun `Collection elements are wrapped element-wise`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        val elements = proxy.greeters()

        assertThrows(PluginFatalException::class.java) { elements.first().fail() }
    }

    /**
     * Use case: `Map<String, Greeter>` return values are wrapped over their values, keys stay as-is.
     */
    @Test
    fun `Map values are wrapped, keys stay unchanged`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        val byLabel = proxy.greetersByLabel()

        assertEquals(setOf("a"), byLabel.keys)
        assertThrows(PluginFatalException::class.java) { byLabel.getValue("a").fail() }
    }

    /**
     * Use case: `Array<Greeter>` return values are wrapped element-wise.
     */
    @Test
    fun `Array elements are wrapped element-wise`() {
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), strategy) {}

        val array = proxy.greeterArray()

        assertThrows(PluginFatalException::class.java) { array[0].fail() }
        assertArrayEquals(arrayOf(1), arrayOf(array.size))
    }

    /**
     * Use case: [ExceptionHandlingAction.IGNORE] and [ExceptionHandlingAction.UNLOAD] are reachable
     * directly through a custom [ExceptionHandlingStrategy], independent of the default matrix.
     */
    @Test
    fun `custom exception handling strategy is honoured`() {
        var unloaded = false
        val alwaysIgnore = ExceptionHandlingStrategy { ExceptionHandlingAction.IGNORE }
        val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), alwaysIgnore) { unloaded = true }

        assertThrows(PluginExecutionException::class.java) { proxy.fail() }
        assertFalse(unloaded)
    }

    /**
     * Use case: [ExceptionHandlingAction.CRASH] calls `Throwable.printStackTrace()` before halting
     * the JVM, verified through the swappable [PluginCrashHandler] test seam instead of actually
     * killing the test process.
     */
    @Test
    fun `CRASH prints the stack trace before invoking the crash seam`() {
        val originalAction = PluginCrashHandler.action
        var seamInvokedWith: Throwable? = null
        PluginCrashHandler.action = { throwable ->
            seamInvokedWith = throwable
            throw AssertionError("test seam stand-in for Runtime.halt()")
        }
        try {
            val alwaysCrash = ExceptionHandlingStrategy { ExceptionHandlingAction.CRASH }
            val proxy = ExtensionProxyFactory.create(Greeter::class.java, OpenGreeterImpl(), alwaysCrash) {}

            assertThrows(AssertionError::class.java) { proxy.fail() }
            assertTrue(seamInvokedWith is IllegalStateException)
        } finally {
            PluginCrashHandler.action = originalAction
        }
    }

    /**
     * Use case: a call governed by a [PluginSandbox] (IP-03) that exceeds its configured
     * [PluginSandboxPolicy.callTimeout] resolves like any other escaping `Throwable` - by default,
     * to [PluginFatalException] - instead of blocking the caller forever.
     */
    @Test
    fun `a hanging governed call resolves via the exception handling strategy instead of blocking forever`() {
        val proxy = ExtensionProxyFactory.create(
            Greeter::class.java,
            OpenGreeterImpl(),
            strategy,
            pluginId = "plugin-a",
            sandbox = PluginSandbox(),
            policy = PluginSandboxPolicy(callTimeout = Duration.ofMillis(50)),
        ) {}

        val thrown = assertThrows(PluginFatalException::class.java) { proxy.hang() }
        assertTrue(thrown.cause is SandboxTimeoutException)
    }

    /**
     * Use case: a recursively wrapped, nested return value (e.g. a factory method's result) is
     * governed by the same [PluginSandbox]/`pluginId`/`policy` as the call that produced it - a
     * hanging call on the nested proxy resolves via the exception handling strategy too, instead of
     * blocking forever just because it is one level removed from the original proxy.
     */
    @Test
    fun `a hanging call on a recursively wrapped nested proxy is governed too`() {
        val proxy = ExtensionProxyFactory.create(
            Greeter::class.java,
            OpenGreeterImpl(),
            strategy,
            pluginId = "plugin-a",
            sandbox = PluginSandbox(),
            policy = PluginSandboxPolicy(callTimeout = Duration.ofMillis(50)),
        ) {}

        val nested = proxy.makeFactory()
        val thrown = assertThrows(PluginFatalException::class.java) { nested.hang() }

        assertTrue(thrown.cause is SandboxTimeoutException)
    }

    /**
     * Use case: a `Set<Greeter>` return value stays a `Set` after wrapping - rebuilding it as a `List`
     * would make the JDK proxy fail with a `ClassCastException` at the caller - and its elements are
     * still wrapped and enforced.
     */
    @Test
    fun `Set return values keep their Set type and wrap the elements`() {
        val proxy = ExtensionProxyFactory.create(CollectionGreeter::class.java, CollectionGreeterImpl(), strategy) {}

        val set: Set<CollectionGreeter> = proxy.greeterSet()

        assertEquals(1, set.size)
        assertThrows(PluginFatalException::class.java) { set.first().fail() }
    }

    /**
     * Use case: a `SortedSet` cannot be rebuilt around proxied elements without losing its ordering
     * contract, so the very same instance is passed through unchanged.
     */
    @Test
    fun `SortedSet return values are passed through unchanged`() {
        val impl = CollectionGreeterImpl()
        val proxy = ExtensionProxyFactory.create(CollectionGreeter::class.java, impl, strategy) {}

        org.junit.jupiter.api.Assertions.assertSame(impl.sortedSet, proxy.greeterSortedSet())
    }

    /**
     * Use case: a `SortedMap` cannot be rebuilt around proxied values without losing its ordering
     * contract, so the very same instance is passed through unchanged.
     */
    @Test
    fun `SortedMap return values are passed through unchanged`() {
        val impl = CollectionGreeterImpl()
        val proxy = ExtensionProxyFactory.create(CollectionGreeter::class.java, impl, strategy) {}

        org.junit.jupiter.api.Assertions.assertSame(impl.sortedMap, proxy.greeterSortedMap())
    }
}

interface CollectionGreeter {
    fun fail(): String
    fun greeterSet(): Set<CollectionGreeter>
    fun greeterSortedSet(): java.util.SortedSet<CollectionGreeter>
    fun greeterSortedMap(): java.util.SortedMap<String, CollectionGreeter>
}

class CollectionGreeterImpl : CollectionGreeter {
    val sortedSet: java.util.SortedSet<CollectionGreeter> =
        java.util.TreeSet(compareBy<CollectionGreeter> { System.identityHashCode(it) })
    val sortedMap: java.util.SortedMap<String, CollectionGreeter> = java.util.TreeMap()

    override fun fail(): String = throw IllegalStateException("boom")
    override fun greeterSet(): Set<CollectionGreeter> = linkedSetOf(CollectionGreeterImpl())
    override fun greeterSortedSet(): java.util.SortedSet<CollectionGreeter> = sortedSet
    override fun greeterSortedMap(): java.util.SortedMap<String, CollectionGreeter> = sortedMap
}
