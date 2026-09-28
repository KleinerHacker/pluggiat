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

package org.pcsoft.framework.pluggiat.sandbox.process

import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxStrategy
import org.pcsoft.framework.pluggiat.sandbox.SandboxCheckResult
import org.pcsoft.framework.pluggiat.sandbox.SandboxIsolationLevel
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxAgentNotActiveException
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessResponse
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Duration

/**
 * IP-04's process isolation, wired into [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox] as a
 * dedicated collaborator - analogous to [org.pcsoft.framework.pluggiat.sandbox.ThreadWatchdog] -
 * even though it also implements [PluginSandboxStrategy] so its [activate] hook still runs uniformly
 * with every other strategy.
 *
 * **Deviation from the literal IP-04 implementation plan wording**: rather than introducing a
 * separate `PluginLoader.load` overload and a `LoadedPlugin`/`PluginLoadResult` variant without a
 * `PluginClassLoader` (as originally sketched), a process-isolated plugin is still loaded in-VM via
 * the regular [org.pcsoft.framework.pluggiat.classloader.PluginLoader] path (for manifest parsing,
 * dependency resolution and extension-point/configuration mapping only - its extension
 * implementation classes are never *instantiated* in-VM). The subprocess itself is started lazily,
 * on first [createExtensionProxy] call for a given plugin id, from the plugin's own JAR(s) on disk
 * (see [PluginProcessClasspath]). This was the smallest change that keeps every existing
 * `LoadedPlugin`/`ExtensionAggregator` caller working unchanged, at the cost of the plugin's JAR(s)
 * being class-loaded (but never instantiated) a second time, redundantly, in the host - see the
 * `sandbox.md` MkDocs page for the full rationale and its accepted TOCTOU implication
 * ([PluginProcessClasspath] re-reads the JAR from disk instead of using the security-pinned bytes).
 *
 * @property startupTimeout upper bound for a subprocess's startup handshake, see [PluginProcessManager.start]
 */
class ProcessIsolationStrategy(
    private val startupTimeout: Duration = Duration.ofSeconds(15),
) : PluginSandboxStrategy {
    private val logger = LoggerFactory.getLogger(ProcessIsolationStrategy::class.java)

    /** Invoked whenever a subprocess crashes or exits unexpectedly - wired by [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox]. */
    var onViolation: (pluginId: String, violation: SandboxViolation) -> Unit = { _, _ -> }

    private val processManager: PluginProcessManager by lazy {
        PluginProcessManager(onCrash = { pluginId ->
            logger.trace("Forwarding subprocess crash of plugin '{}' as a sandbox violation", pluginId)
            onViolation(
                pluginId,
                SandboxViolation(pluginId, category = null, reason = "Process-isolated plugin subprocess crashed or exited unexpectedly"),
            )
        })
    }

    /**
     * Verifies that a restricted process-isolated plugin can actually be mediated, then returns
     * success: process isolation's own enforcement (subprocess start) happens lazily, from
     * [createExtensionProxy], once the plugin's own JAR path (not available to
     * [PluginSandboxStrategy.activate]) is known - see this class's KDoc.
     *
     * A [policy] that restricts at least one API category needs the sandbox agent inside the
     * subprocess (it is what instruments the plugin's classes there, see [PluginProcessManager]), and
     * the agent can only be handed to the subprocess when the framework itself runs from its own JAR.
     * Without it, the subprocess would start and run the plugin *unmediated* - so activation fails here
     * instead, exactly as [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy] fails for
     * an in-VM plugin whose JVM was started without `-javaagent`.
     */
    override fun activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult {
        logger.trace(
            "ProcessIsolationStrategy.activate for plugin '{}': isolationLevel={}, requiresApiMediation={}",
            loadedPlugin.pluginId, policy.isolationLevel, policy.requiresApiMediation,
        )
        if (policy.isolationLevel == SandboxIsolationLevel.PROCESS &&
            policy.requiresApiMediation &&
            PluginProcessManager.sandboxAgentJar() == null
        ) {
            throw SandboxAgentNotActiveException(loadedPlugin.pluginId)
        }
        return SandboxCheckResult.Success
    }

    /**
     * Creates (starting the subprocess on first use for [pluginId]) a `java.lang.reflect.Proxy` of
     * [apiType] whose every call is encoded via [org.pcsoft.framework.pluggiat.sandbox.process.der.DerCodec]
     * and sent to [pluginId]'s subprocess, which instantiates/invokes [implementationClassName] there.
     *
     * A method whose signature [SandboxTypeSupport] rejects throws [UnsupportedSandboxTypeException]
     * immediately, without ever starting the subprocess or sending anything - see [SandboxTypeSupport.requireSupported].
     *
     * @param pinnedContent the plugin's security-checked bytes, handed to [PluginProcessManager] so the
     * subprocess is started from exactly those bytes instead of from whatever is at [pluginPath] by
     * then; `null` only for a candidate that was never pinned
     * @throws ProcessIsolationStartupException if the subprocess for [pluginId] could not be started
     */
    fun createExtensionProxy(
        pluginId: String,
        pluginPath: Path,
        pinnedContent: PinnedPluginContent?,
        apiType: Class<*>,
        implementationClassName: String,
        policy: PluginSandboxPolicy,
    ): Any {
        // Deliberately lazy: the subprocess is only started by the first *supported* call (see
        // invokeRemote) - an unsupported method must never trigger a subprocess start at all.
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ProcessIsolatedProxy($implementationClassName@$pluginId)"
                else -> invokeRemote(pluginId, pluginPath, pinnedContent, implementationClassName, method, args, policy)
            }
        }
        return Proxy.newProxyInstance(apiType.classLoader, arrayOf(apiType), handler)
    }

    private fun ensureStarted(
        pluginId: String,
        pluginPath: Path,
        pinnedContent: PinnedPluginContent?,
        policy: PluginSandboxPolicy,
    ): ProcessIpcClient = processManager.start(pluginId, pluginPath, pinnedContent, policy, startupTimeout)

    private fun invokeRemote(
        pluginId: String,
        pluginPath: Path,
        pinnedContent: PinnedPluginContent?,
        implementationClassName: String,
        method: Method,
        args: Array<out Any?>?,
        policy: PluginSandboxPolicy,
    ): Any? {
        SandboxTypeSupport.requireSupported(method)
        val ipcClient = ensureStarted(pluginId, pluginPath, pinnedContent, policy)

        val call = ProcessCall(
            implementationClassName = implementationClassName,
            methodName = method.name,
            arguments = (args ?: emptyArray()).map { SandboxTypeSupport.encode(it) },
        )
        logger.trace("Forwarding process-isolated call '{}.{}' for plugin '{}' to its subprocess", implementationClassName, method.name, pluginId)
        val response = try {
            ipcClient.call(call)
        } catch (e: java.io.IOException) {
            // A java.lang.reflect.Proxy would otherwise wrap this checked exception in an
            // UndeclaredThrowableException (the proxied interface method declares no checked
            // exceptions) - rethrow as an unchecked type instead, exactly like any other Throwable an
            // in-VM extension call could raise.
            throw ProcessIsolationIoException(pluginId, method, e)
        }
        return when (response) {
            is ProcessResponse.Success -> SandboxTypeSupport.decode(response.value, method.genericReturnType)
            is ProcessResponse.Failure -> throw ProcessIsolatedCallException(pluginId, method, response.message)
        }
    }

    /**
     * Releases [pluginId]'s subprocess (if any) - called by
     * [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.deactivate].
     */
    fun stop(pluginId: String) {
        processManager.stop(pluginId)
    }
}

/**
 * Thrown when [method] raised a `Throwable` inside a process-isolated plugin's subprocess - the
 * counterpart, across the process boundary, to a plain exception escaping an in-VM extension call.
 */
class ProcessIsolatedCallException(pluginId: String, method: Method, remoteMessage: String) :
    RuntimeException("Plugin '$pluginId' method '${method.name}' raised an exception in its subprocess: $remoteMessage")

/**
 * Thrown when the IPC socket call for [method] failed at the transport level (subprocess crash,
 * connection reset, ...) - wraps the original checked [java.io.IOException] as its `cause` so it is
 * never swallowed, but as an unchecked type so `java.lang.reflect.Proxy` propagates it directly
 * instead of wrapping it again in `UndeclaredThrowableException`.
 */
class ProcessIsolationIoException(pluginId: String, method: Method, cause: java.io.IOException) :
    RuntimeException("Plugin '$pluginId' method '${method.name}' failed at the IPC transport level: ${cause.message}", cause)
