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

import com.example.sandboxfixture.SandboxAgentFixtureJar
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.classloader.SdkWhitelistEntry
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.sandbox.agent.PluginSandboxAgent
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxViolationException

/**
 * End-to-end verification of IP-02's API mediation with the `pluggiat` Java agent actually installed -
 * the one thing the regular `test` source set cannot cover, since that JVM deliberately runs without
 * `-javaagent` (see the `sandboxAgentTest` task in `build.gradle.kts`).
 *
 * A "plugin" here is the compiled fixture packaged as a JAR and loaded through a real
 * [PluginClassLoader], so its classes go through the agent's transformer exactly as a real plugin's
 * would; its policy is activated through the public [PluginSandbox] facade rather than by touching the
 * guard registry directly.
 */
class SandboxAgentMediationTest {

    private fun manifest(pluginId: String): PluginManifest =
        PluginManifest(id = pluginId, name = pluginId, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

    /**
     * The fixture JAR behind a real plugin class loader.
     *
     * `kotlin` is whitelisted because the fixture is Kotlin code and its compiler-generated intrinsics
     * have to resolve somewhere - exactly what a host exposing its own Kotlin-based SDK would configure.
     * `com.intellij.rt.coverage` is whitelisted for this test run only: the coverage agent of the build's
     * coverage measurement transforms *every* loaded class, including the ones this plugin class loader
     * defines, and the code it injects references its own runtime. Without that entry the fixture fails
     * with a `NoClassDefFoundError` from the coverage runtime rather than from anything this test is about.
     */
    private fun pluginClassLoader(jar: Path): PluginClassLoader = PluginClassLoader(
        arrayOf(jar.toUri().toURL()),
        javaClass.classLoader,
        listOf(SdkWhitelistEntry("kotlin"), SdkWhitelistEntry("com.intellij.rt.coverage")),
        emptyList(),
    )

    /**
     * Use case: the agent is actually installed in this JVM - the precondition every other test here
     * depends on, asserted explicitly so a misconfigured `-javaagent` fails as a clear statement instead
     * of as a confusing "nothing was blocked".
     */
    @Test
    fun `sandbox agent is active in this JVM`() {
        assertTrue(PluginSandboxAgent.isActive)
    }

    /**
     * Use case: a plugin class performing a guarded call (`java.io.File`, i.e. `FILESYSTEM`) under a
     * policy that allows no category at all has that call aborted with [SandboxViolationException], and
     * the violation is reported to the host's listener - the full path from injected guard call through
     * [org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry] to [PluginSandbox].
     */
    @Test
    fun `guarded call from an instrumented plugin class is blocked and reported`() {
        val pluginId = "agent-blocked-fixture"
        val classLoader = pluginClassLoader(SandboxAgentFixtureJar.writeJar())
        val sandbox = PluginSandbox()
        val reported = mutableListOf<SandboxViolation>()
        sandbox.violationListener = { _, violation -> reported += violation }
        sandbox.activate(LoadedPlugin(pluginId, manifest(pluginId), classLoader), PluginSandboxPolicy(allowedApiCategories = emptySet()))

        try {
            val instance = classLoader.loadClass(SandboxAgentFixtureJar.IMPLEMENTATION_CLASS_NAME).getDeclaredConstructor().newInstance()
            val invocationError = assertThrows(InvocationTargetException::class.java) {
                instance.javaClass.getMethod("touchFilesystem").invoke(instance)
            }

            val violation = assertInstanceOf(SandboxViolationException::class.java, invocationError.targetException)
            assertEquals(SandboxApiCategory.FILESYSTEM, violation.violation.category)
            assertEquals(pluginId, violation.violation.pluginId)
            assertEquals(1, reported.size)
            assertEquals(SandboxApiCategory.FILESYSTEM, reported.single().category)
        } finally {
            sandbox.deactivate(pluginId, classLoader)
            classLoader.close()
        }
    }

    /**
     * Use case: a guarded call whose category *is* allowed runs through the injected guard unchanged -
     * it neither throws nor fails with a [NoClassDefFoundError], which is what would happen if the
     * instrumented plugin could not resolve the framework's guard class (see the framework-package
     * delegation in [PluginClassLoader]).
     */
    @Test
    fun `allowed category passes the injected guard without a linkage error`() {
        val pluginId = "agent-allowed-fixture"
        val classLoader = pluginClassLoader(SandboxAgentFixtureJar.writeJar())
        val sandbox = PluginSandbox()
        sandbox.activate(
            LoadedPlugin(pluginId, manifest(pluginId), classLoader),
            PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.FILESYSTEM)),
        )

        try {
            val instance = classLoader.loadClass(SandboxAgentFixtureJar.IMPLEMENTATION_CLASS_NAME).getDeclaredConstructor().newInstance()

            val result = instance.javaClass.getMethod("touchFilesystem").invoke(instance)
            val echo = instance.javaClass.getMethod("harmlessEcho", String::class.java).invoke(instance, "ok")

            assertFalse(result as Boolean)
            assertEquals("OK", echo)
        } finally {
            sandbox.deactivate(pluginId, classLoader)
            classLoader.close()
        }
    }

    /**
     * Use case: a plugin shipping its own `SandboxGuardRegistry` class - here as deliberately invalid
     * bytecode - does not get that class loaded: the host's copy is returned instead, so the guard calls
     * injected into the plugin's bytecode cannot be redirected into a registry the plugin controls
     * (which would allow every category). Had the plugin's entry been used, defining it would have
     * failed with a `ClassFormatError` instead.
     */
    @Test
    fun `plugin cannot shadow the frameworks guard registry`() {
        val classLoader = pluginClassLoader(SandboxAgentFixtureJar.writeJarWithForgedFrameworkEntries())

        try {
            val registryFromPlugin = classLoader.loadClass("org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry")

            assertEquals(javaClass.classLoader, registryFromPlugin.classLoader)
        } finally {
            classLoader.close()
        }
    }
}
