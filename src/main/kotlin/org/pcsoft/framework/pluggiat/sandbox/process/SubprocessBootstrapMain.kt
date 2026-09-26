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

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Entry point (`main`) of a process-isolated plugin's subprocess JVM, started by
 * [PluginProcessManager] via `ProcessBuilder` with this class as its main class.
 *
 * Argument contract (positional, all required):
 * 1. one or more plugin JAR file paths, separated by [java.io.File.pathSeparator] (a single
 *    argument, like a classpath string)
 *
 * Builds a plain [URLClassLoader] (parent = the platform class loader only, deliberately *not* the
 * launching JVM's application class loader - the subprocess must only ever see the plugin's own
 * classes plus the JDK, exactly like [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader]
 * enforces in-VM) over those JARs, starts a [ProcessIpcServer] on an OS-assigned loopback port,
 * prints exactly one line - `PLUGGIAT-PORT:<port>` - to stdout so [PluginProcessManager] can read
 * the assigned port back, then serves IPC calls until the process is killed.
 *
 * There is deliberately no OS-level user/process separation here (see the FP-002 feature plan,
 * section 9): the subprocess runs as the same OS user as the host, isolated only by being a
 * different JVM process - it prevents an in-VM crash/hang/API-mediation bypass from affecting the
 * host, not a malicious plugin from touching the host's other OS-level resources.
 */
object SubprocessBootstrapMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Usage: SubprocessBootstrapMain <classpath>" }
        val urls = args[0].split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray<URL>()
        val classLoader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

        val server = ProcessIpcServer(classLoader)
        // Machine-readable handshake line - stdout is otherwise unused by the subprocess.
        println("PLUGGIAT-PORT:${server.port}")
        System.out.flush()

        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        server.serveForever()
    }
}
