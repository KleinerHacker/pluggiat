package org.pcsoft.framework.pluggiat.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class DefaultExceptionHandlingStrategyTest {

    private val strategy = DefaultExceptionHandlingStrategy()

    /**
     * Use case: `PluginExecutionException` resolves to `IGNORE`, per the standard matrix.
     */
    @Test
    fun `PluginExecutionException resolves to IGNORE`() {
        assertEquals(ExceptionHandlingAction.IGNORE, strategy.resolve(PluginExecutionException("nope")))
    }

    /**
     * Use case: `PluginFatalException` resolves to `UNLOAD`, per the standard matrix.
     */
    @Test
    fun `PluginFatalException resolves to UNLOAD`() {
        assertEquals(ExceptionHandlingAction.UNLOAD, strategy.resolve(PluginFatalException("fatal")))
    }

    /**
     * Use case: any other checked exception (an `Exception` that is not a `RuntimeException`)
     * resolves to `IGNORE`.
     */
    @Test
    fun `other checked exception resolves to IGNORE`() {
        assertEquals(ExceptionHandlingAction.IGNORE, strategy.resolve(IOException("disk full")))
    }

    /**
     * Use case: any other unchecked exception (`RuntimeException`) resolves to `UNLOAD`.
     */
    @Test
    fun `other unchecked exception resolves to UNLOAD`() {
        assertEquals(ExceptionHandlingAction.UNLOAD, strategy.resolve(IllegalStateException("boom")))
    }

    /**
     * Use case: an `Error` (not an `Exception` at all) resolves to `UNLOAD` as well.
     */
    @Test
    fun `Error resolves to UNLOAD`() {
        assertEquals(ExceptionHandlingAction.UNLOAD, strategy.resolve(OutOfMemoryError("simulated")))
    }

    /**
     * Use case: a custom matrix entry for a specific exception class takes precedence over the
     * standard checked/unchecked rule.
     */
    @Test
    fun `custom matrix entry overrides the standard rule`() {
        val custom = DefaultExceptionHandlingStrategy(matrix = mapOf(IllegalStateException::class to ExceptionHandlingAction.IGNORE))

        assertEquals(ExceptionHandlingAction.IGNORE, custom.resolve(IllegalStateException("boom")))
    }

    /**
     * Use case: the class hierarchy walk picks the most specific matching superclass - a subclass
     * of a matrix-registered class resolves to that entry's action even without its own entry.
     */
    @Test
    fun `hierarchy walk resolves a subclass via its registered superclass`() {
        val custom = DefaultExceptionHandlingStrategy(matrix = mapOf(RuntimeException::class to ExceptionHandlingAction.IGNORE))

        assertEquals(ExceptionHandlingAction.IGNORE, custom.resolve(IllegalStateException("boom")))
    }

    /**
     * Use case: when the strategy's own matrix has no matching entry, resolution falls back to a
     * configured [parent] strategy instead of the standard rule.
     */
    @Test
    fun `falls back to the configured parent strategy when no entry matches`() {
        val parent = ExceptionHandlingStrategy { ExceptionHandlingAction.IGNORE }
        val nested = DefaultExceptionHandlingStrategy(matrix = emptyMap(), parent = parent)

        assertEquals(ExceptionHandlingAction.IGNORE, nested.resolve(IllegalStateException("boom")))
    }
}
