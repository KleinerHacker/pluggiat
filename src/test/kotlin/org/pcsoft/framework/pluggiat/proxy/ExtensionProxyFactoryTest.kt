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

interface Greeter {
    fun greet(name: String): String
    fun fail(): String
    fun makeFactory(): Greeter
    fun greeters(): List<Greeter>
    fun greetersByLabel(): Map<String, Greeter>
    fun greeterArray(): Array<Greeter>
    fun asText(): String
    fun asFinal(): FinalPayload
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
}
