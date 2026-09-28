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

import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Verifies [PluginSandboxAgent]'s installation logic against a stand-in [Instrumentation] (the test JVM
 * deliberately runs without the real agent) and the matcher / transformer it registers.
 */
class PluginSandboxAgentTest {

    private class Sample

    private var addedTransformers = 0

    /** An [Instrumentation] whose methods return neutral defaults and which counts registered transformers. */
    private fun fakeInstrumentation(): Instrumentation {
        val handler = InvocationHandler { _, method, _ ->
            if (method.name == "addTransformer") addedTransformers++
            val returnType = method.returnType
            when {
                returnType == java.lang.Boolean.TYPE -> false
                returnType == Integer.TYPE -> 0
                returnType == java.lang.Long.TYPE -> 0L
                returnType.isArray -> java.lang.reflect.Array.newInstance(returnType.componentType, 0)
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            Instrumentation::class.java.classLoader, arrayOf(Instrumentation::class.java), handler,
        ) as Instrumentation
    }

    @AfterEach
    fun resetAgentState() {
        // The agent is a process-wide singleton; other tests rely on it being inactive.
        val field = PluginSandboxAgent::class.java.getDeclaredField("isActive")
        field.isAccessible = true
        field.setBoolean(null, false)
    }

    private fun privateInstance(className: String): Any {
        val type = Class.forName("org.pcsoft.framework.pluggiat.sandbox.agent.$className")
        val field = type.getDeclaredField("INSTANCE")
        field.isAccessible = true
        return field.get(null)
    }

    /**
     * Use case: `premain` (JVM start with `-javaagent`) installs the transformer and marks the agent
     * active; a second start does not install a second transformer.
     */
    @Test
    fun `premain installs the transformer once and a second call is ignored`() {
        val instrumentation = fakeInstrumentation()

        PluginSandboxAgent.premain(null, instrumentation)
        val afterFirst = addedTransformers
        PluginSandboxAgent.premain(null, instrumentation)

        assertTrue(PluginSandboxAgent.isActive)
        assertTrue(afterFirst > 0)
        assertEquals(afterFirst, addedTransformers)
    }

    /**
     * Use case: `agentmain` (dynamic attach) installs the transformer and marks the agent active.
     */
    @Test
    fun `agentmain installs the transformer and activates the agent`() {
        assertFalse(PluginSandboxAgent.isActive)

        PluginSandboxAgent.agentmain("ignored-args", fakeInstrumentation())

        assertTrue(PluginSandboxAgent.isActive)
        assertTrue(addedTransformers > 0)
    }

    /**
     * Use case: the registered matcher selects a type loaded by a [PluginClassLoader] and rejects a
     * type of any other loader (or of the bootstrap loader, i.e. a `null` loader).
     */
    @Test
    fun `the matcher only accepts types of a PluginClassLoader`() {
        val matcher = privateInstance("PluginClassLoaderRawMatcher") as AgentBuilder.RawMatcher
        val type = TypeDescription.ForLoadedType(Sample::class.java)
        val pluginLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList())

        assertTrue(matcher.matches(type, pluginLoader, null, null, null))
        assertFalse(matcher.matches(type, javaClass.classLoader, null, null, null))
        assertFalse(matcher.matches(type, null, null, null, null))
        pluginLoader.close()
    }

    /**
     * Use case: the registered transformer adds the guard visitor to the builder and returns a builder
     * for the same type.
     */
    @Test
    fun `the transformer applies the guard visitor to the builder`() {
        val transformer = privateInstance("GuardTransformer") as AgentBuilder.Transformer
        val builder: DynamicType.Builder<*> = ByteBuddy().redefine(Sample::class.java)

        val result = transformer.transform(builder, TypeDescription.ForLoadedType(Sample::class.java), null, null, null)

        assertNotNull(result)
    }
}
