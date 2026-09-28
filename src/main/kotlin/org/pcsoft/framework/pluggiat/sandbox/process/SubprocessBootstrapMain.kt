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

import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry
import java.net.URL
import java.nio.file.Path

/**
 * Entry point of a process-isolated plugin's JVM subprocess, started by [PluginProcessManager].
 *
 * Program arguments, in order:
 * 1. the plugin's classpath (platform-separated JAR paths, materialized from its security-checked
 *    bytes by [PluginProcessClasspath]),
 * 2. the plugin id (for violation reporting inside this subprocess),
 * 3. the IPC token every incoming call has to present (see [ProcessIpcServer]),
 * 4. the comma-separated names of the [SandboxApiCategory] values the plugin's effective policy allows
 *    (empty means: none).
 *
 * The subprocess reproduces the host's two enforcement mechanisms rather than relying on process
 * separation alone:
 *
 * * the plugin is loaded through a [PluginClassLoader], so the agent (passed as `-javaagent` by
 *   [PluginProcessManager]) instruments its classes here just as it would in the host, and so
 *   pluggiat's own classes - above all [SandboxGuardRegistry], which the injected guard calls resolve
 *   against - always come from this subprocess's own copy instead of from the plugin's JARs;
 * * the plugin's policy is registered in this subprocess's [SandboxGuardRegistry], so a guarded call
 *   made in here is blocked by the same rules as in the host. Without it, the registry would hold no
 *   entry for the plugin's class loader and every guarded call would pass - process isolation would
 *   then be a *weaker* sandbox than in-VM mediation, which is the opposite of what it is chosen for.
 */
object SubprocessBootstrapMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // SECURITY: all four arguments are mandatory - a subprocess started without a token or without its
        // SECURITY: category list must not fall back to "no authentication" or "everything allowed".
        require(args.size >= 4) {
            "Usage: SubprocessBootstrapMain <classpath> <pluginId> <ipcToken> <allowedApiCategories>"
        }
        val urls = args[0].split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray<URL>()
        val pluginId = args[1]
        val token = args[2]
        // SECURITY: the policy is rebuilt here from the host's allow-list; it is what the injected guard
        // SECURITY: calls inside this subprocess enforce.
        val policy = PluginSandboxPolicy(allowedApiCategories = parseCategories(args[3]))

        // hostClassLoader is this subprocess's own application class loader: it carries pluggiat itself
        // (the subprocess is started with the host's -cp), which is what the framework-package
        // delegation in PluginClassLoader resolves against. The SDK whitelist stays empty - a subprocess
        // has no host application to expose, so a process-isolated plugin must be self-contained.
        // SECURITY: a PluginClassLoader (not a plain URLClassLoader): it is what the agent matches on, so the
        // SECURITY: plugin's classes get instrumented in here, and it keeps framework classes coming from this
        // SECURITY: subprocess's own copy rather than from the plugin's JARs.
        val classLoader = PluginClassLoader(
            urls,
            SubprocessBootstrapMain::class.java.classLoader,
            // SECURITY: empty SDK whitelist - a subprocess exposes no host packages at all.
            emptyList(),
            emptyList(),
        )

        // SECURITY: without this registration the subprocess's registry would hold no entry for the plugin's
        // SECURITY: loader and every guarded call would pass - process isolation would be weaker than in-VM.
        SandboxGuardRegistry.register(classLoader, pluginId, policy) { id, violation ->
            // The host drains this subprocess's stderr into its own log, so a violation in here shows up
            // there; the guard call additionally throws, which travels back to the host as a failed call.
            System.err.println("SECURITY WARNING - potential attack: plugin '$id' violated its sandbox policy: ${violation.reason}")
        }

        val server = ProcessIpcServer(classLoader, token)
        println("PLUGGIAT-PORT:${server.port}")
        System.out.flush()

        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        server.serveForever()
    }

    /**
     * Parses the allowed-category argument. An unknown name fails the subprocess at startup (the host
     * then reports a failed handshake) instead of being skipped: a policy that silently lost one of its
     * restrictions is worse than a plugin that does not start.
     */
    private fun parseCategories(argument: String): Set<SandboxApiCategory> =
        argument.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // SECURITY: valueOf throws on an unknown name, failing the subprocess at startup - a policy that
            // SECURITY: silently lost one of its restrictions is worse than a plugin that does not start.
            .map { SandboxApiCategory.valueOf(it) }
            .toSet()
}
