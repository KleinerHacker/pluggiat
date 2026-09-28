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

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.slf4j.LoggerFactory
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * The `pluggiat` Java agent: installs a class file transformer that instruments every class loaded
 * by a [PluginClassLoader] with guard checks in front of risk-bearing JDK API calls (see
 * [GuardAsmVisitorWrapper]).
 *
 * A host wanting to enforce a [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy] that
 * restricts any [org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory] must start its JVM with
 * this module's own JAR as a `-javaagent` (its manifest declares both [premain] and [agentmain] as
 * entry points, see `build.gradle.kts`), e.g.:
 * `java -javaagent:pluggiat-<version>.jar -jar host-app.jar`.
 *
 * [isActive] is checked by [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy]
 * before activating a policy that requires it - without a `-javaagent` start, [isActive] stays
 * `false` and any such policy fails fast with a [SandboxAgentNotActiveException] instead of silently
 * granting unmediated access.
 */
object PluginSandboxAgent {
    private val logger = LoggerFactory.getLogger(PluginSandboxAgent::class.java)

    /** Whether the agent's transformer was successfully installed on the running JVM. */
    @Volatile
    var isActive: Boolean = false
        private set

    /**
     * JVM-standard entry point when this module's JAR is passed via `-javaagent` at JVM start.
     */
    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        install(instrumentation)
    }

    /**
     * JVM-standard entry point for a dynamic attach (`Attach API`) after JVM start. Not the
     * recommended activation path on JDK 21+ (see [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy]),
     * but wired for completeness/testability.
     */
    @JvmStatic
    fun agentmain(agentArgs: String?, instrumentation: Instrumentation) {
        install(instrumentation)
    }

    @Synchronized
    private fun install(instrumentation: Instrumentation) {
        // SECURITY: installed at most once; a second transformer would inject a second guard call per call
        // SECURITY: site, which is not more secure, only slower and harder to reason about.
        if (isActive) return

        logger.trace("Installing pluggiat sandbox Java agent via strategy AgentInstrumentationStrategy (Byte Buddy AgentBuilder)")
        AgentBuilder.Default()
            // SECURITY: instruments method bodies only - no added members, no auxiliary types, and above
            // SECURITY: all no injected static initializer. Byte Buddy's default self-injection strategy
            // SECURITY: adds a <clinit> to every transformed class that reaches its own bookkeeping
            // SECURITY: reflectively; that injected code sits *inside* the plugin class, so this very
            // SECURITY: transformer would guard it and a plugin whose policy blocks REFLECTION would fail
            // SECURITY: during class initialization - before any of its own code ever ran. Freezing the
            // SECURITY: class format keeps the transformation to exactly what GuardAsmVisitorWrapper emits.
            .disableClassFormatChanges()
            // SECURITY: matches on the class *loader*, not on class names: every class a PluginClassLoader
            // SECURITY: defines is instrumented, so a plugin cannot avoid mediation by choosing its package.
            .type(PluginClassLoaderRawMatcher)
            .transform(GuardTransformer)
            .installOn(instrumentation)

        // SECURITY: only set after a successful install - AgentInstrumentationStrategy refuses to activate a
        // SECURITY: restrictive policy while this is false, instead of running the plugin unmediated.
        isActive = true
        logger.info("pluggiat sandbox agent installed - plugin classes are now instrumented for API mediation")
    }
}

/**
 * Matches every type loaded by a [PluginClassLoader] - the set of classes [PluginSandboxAgent]
 * instruments, regardless of which specific plugin they belong to (per-plugin policy enforcement
 * happens at runtime via [SandboxGuardRegistry], not at transform time).
 */
private object PluginClassLoaderRawMatcher : AgentBuilder.RawMatcher {
    override fun matches(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        module: JavaModule?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        // SECURITY: exactly the plugin loaders and nothing else: host and JDK classes stay untransformed, so
        // SECURITY: the framework's own guard code can never end up guarding itself.
    ): Boolean = classLoader is PluginClassLoader
}

/**
 * Applies [GuardAsmVisitorWrapper] to every type matched by [PluginClassLoaderRawMatcher].
 */
private object GuardTransformer : AgentBuilder.Transformer {
    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        module: JavaModule?,
        protectionDomain: ProtectionDomain?,
    ): DynamicType.Builder<*> = builder.visit(GuardAsmVisitorWrapper)
}
