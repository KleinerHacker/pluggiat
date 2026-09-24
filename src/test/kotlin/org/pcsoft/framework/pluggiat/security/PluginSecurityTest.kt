package org.pcsoft.framework.pluggiat.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.persistence.PluginPersistenceStrategy
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import java.nio.file.Path

class PluginSecurityTest {

    private class FixedResultStrategy(private val result: PluginSecurityCheckResult) : PluginSecurityStrategy {
        var invoked: Boolean = false
            private set

        override fun check(result: PluginScanResult): PluginSecurityCheckResult {
            invoked = true
            return this.result
        }
    }

    private fun scanResult(location: PluginLocation): PluginScanResult {
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")
        return PluginScanResult(location, Path.of("plugin-a.jar"), manifest, PluginScanStatus.LOADED)
    }

    /**
     * Use case: a location without any configured strategy - neither override nor type default -
     * fails evaluation with a configuration error, rather than silently succeeding or failing.
     */
    @Test
    fun `throws when the effective chain is empty`() {
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        assertThrows(IllegalStateException::class.java) {
            PluginSecurity().evaluate(scanResult(location), emptyMap())
        }
    }

    /**
     * Use case: a location's own `securityOverride` takes precedence over the type's default chain.
     */
    @Test
    fun `location override takes precedence over the type default chain`() {
        val overrideStrategy = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val defaultStrategy = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(overrideStrategy))
        val defaults = mapOf(PluginLocationType.EXTERNAL to listOf(defaultStrategy))

        PluginSecurity().evaluate(scanResult(location), defaults)

        assertTrue(overrideStrategy.invoked)
        assertTrue(!defaultStrategy.invoked)
    }

    /**
     * Use case: the chain is checked in order and the first successful strategy ends the check
     * positively, without invoking the remaining strategies.
     */
    @Test
    fun `first successful strategy in the chain ends the check positively`() {
        val first = FixedResultStrategy(PluginSecurityCheckResult.Failure("no"))
        val second = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val third = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(first, second, third))

        val result = PluginSecurity().evaluate(scanResult(location), emptyMap())

        assertEquals(PluginSecurityCheckResult.Success, result)
        assertTrue(first.invoked)
        assertTrue(second.invoked)
        assertTrue(!third.invoked)
    }

    /**
     * Use case: a security problem is only reported once ALL strategies of the chain have failed.
     */
    @Test
    fun `reports failure only once all strategies of the chain have failed`() {
        val first = FixedResultStrategy(PluginSecurityCheckResult.Failure("first failed"))
        val second = FixedResultStrategy(PluginSecurityCheckResult.Failure("second failed"))
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(first, second))

        val result = PluginSecurity().evaluate(scanResult(location), emptyMap())

        assertTrue(result is PluginSecurityCheckResult.Failure)
        assertTrue((result as PluginSecurityCheckResult.Failure).reason.contains("first failed"))
        assertTrue(result.reason.contains("second failed"))
    }

    /**
     * Use case: a custom, framework-external [PluginSecurityStrategy] implementation can be plugged
     * into a location's chain without any framework changes.
     */
    @Test
    fun `custom third-party strategy implementation can be added to the chain`() {
        val customStrategy = object : PluginSecurityStrategy {
            override fun check(result: PluginScanResult): PluginSecurityCheckResult = PluginSecurityCheckResult.Success
        }
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(customStrategy))

        val result = PluginSecurity().evaluate(scanResult(location), emptyMap())

        assertEquals(PluginSecurityCheckResult.Success, result)
    }

    /**
     * Use case: [PluginSecurity.reevaluate] re-reads the candidate from disk via the location's
     * scan strategy and re-evaluates the security chain against the fresh candidate, as the first
     * step of a reactivation flow.
     */
    @Test
    fun `reevaluate re-reads the candidate and evaluates it against the chain`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.writeJar(
            jarPath,
            mapOf("META-INF/plugin.yml" to org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.validManifestYaml("plugin-a")),
        )
        val strategy = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(strategy))

        val result = PluginSecurity().reevaluate(location, jarPath, emptyMap())

        assertEquals(PluginSecurityCheckResult.Success, result)
        assertTrue(strategy.invoked)
    }

    /**
     * Use case: if the candidate is no longer valid on re-check (e.g. the manifest was removed
     * since), [PluginSecurity.reevaluate] fails instead of evaluating a stale result.
     */
    @Test
    fun `reevaluate fails when the candidate is no longer valid on re-check`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val jarPath = tempDir.resolve("plugin-a.jar")
        org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures.writeJar(jarPath, emptyMap())
        val location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy())

        val result = PluginSecurity().reevaluate(location, jarPath, emptyMap())

        assertTrue(result is PluginSecurityCheckResult.Failure)
    }

    /**
     * Use case: a persisted `securityException` flag for the candidate's plugin id skips the chain
     * entirely, resolving to `Success` without invoking any configured strategy.
     */
    @Test
    fun `a persisted security exception skips the chain entirely`() {
        val strategy = FixedResultStrategy(PluginSecurityCheckResult.Failure("would fail"))
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(strategy))
        val persistence = object : PluginPersistenceStrategy {
            override fun read(pluginId: String, key: String): String? =
                if (key == PluginSecurity.SECURITY_EXCEPTION_KEY) "true" else null

            override fun write(pluginId: String, key: String, value: String) {}
        }

        val result = PluginSecurity(persistence).evaluate(scanResult(location), emptyMap())

        assertEquals(PluginSecurityCheckResult.Success, result)
        assertTrue(!strategy.invoked)
    }

    /**
     * Use case: a result without a resolved manifest (no plugin id known yet) never even looks up a
     * security exception, but is otherwise evaluated normally against the chain.
     */
    @Test
    fun `a result without a manifest is evaluated normally, without a security exception lookup`() {
        val strategy = FixedResultStrategy(PluginSecurityCheckResult.Success)
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy(), securityOverride = listOf(strategy))
        val resultWithoutManifest = PluginScanResult(location, Path.of("plugin-a.jar"), null, PluginScanStatus.MANIFEST_INVALID, "broken")

        val result = PluginSecurity().evaluate(resultWithoutManifest, emptyMap())

        assertEquals(PluginSecurityCheckResult.Success, result)
        assertTrue(strategy.invoked)
    }
}
