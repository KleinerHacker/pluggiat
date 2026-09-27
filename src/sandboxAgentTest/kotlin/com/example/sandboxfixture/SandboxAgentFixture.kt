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

package com.example.sandboxfixture

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * The "plugin API" of the sandbox agent test fixture: one method that performs a guarded call and one
 * that performs none, so a test can tell "blocked" apart from "broken".
 *
 * Deliberately outside the `org.pcsoft.framework.pluggiat` namespace: classes below that prefix are
 * always delegated to the host class loader by
 * [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader], so a fixture that has to be loaded
 * *as a plugin* - and therefore instrumented by the agent - must live in a plugin-like package.
 */
interface SandboxAgentFixtureApi {
    /**
     * Touches the file system ([File] is guarded as `FILESYSTEM` on every member, constructor
     * included), so this call is mediated by the sandbox whenever `FILESYSTEM` is not allowed.
     *
     * @return whether the probe file happens to exist - irrelevant to the tests, which only care
     * whether the call is allowed to run at all
     */
    fun touchFilesystem(): Boolean

    /** Touches nothing guarded, so it must keep working under any policy. */
    fun harmlessEcho(input: String): String
}

/**
 * The fixture implementation loaded as "the plugin" - either through a
 * [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader] in this JVM or inside a
 * process-isolated subprocess.
 */
class SandboxAgentFixtureImpl : SandboxAgentFixtureApi {
    override fun touchFilesystem(): Boolean = File("pluggiat-sandbox-agent-probe.txt").exists()

    override fun harmlessEcho(input: String): String = input.uppercase()
}

/**
 * Packages the already-compiled fixture classes into a JAR usable as a plugin candidate, mirroring
 * what `ProcessIsolationStrategyTest` does for its own fixture: the classes are taken from this test
 * run's own classpath, so no separate compilation step is needed.
 */
object SandboxAgentFixtureJar {

    /** Binary name of the implementation class a test asks the framework to load/instantiate. */
    const val IMPLEMENTATION_CLASS_NAME: String = "com.example.sandboxfixture.SandboxAgentFixtureImpl"

    /**
     * Writes a single JAR containing [SandboxAgentFixtureApi] and [SandboxAgentFixtureImpl] into a
     * fresh temporary folder and returns the JAR's path - the candidate for an in-VM plugin load.
     */
    fun writeJar(): Path {
        val folder = Files.createTempDirectory("sandbox-agent-fixture-")
        return writeFixtureJar(folder.resolve("fixture.jar"))
    }

    /**
     * Same fixture, but as a folder candidate that additionally carries a copy of the Kotlin standard
     * library: a process-isolated subprocess has no access to the host's own application classpath, so
     * the compiler-generated intrinsics of these Kotlin classes have to be part of the candidate.
     */
    fun writeFolderWithKotlinStdlib(): Path {
        val folder = Files.createTempDirectory("sandbox-agent-fixture-folder-")
        writeFixtureJar(folder.resolve("fixture.jar"))
        val kotlinStdlibJar = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { Path.of(it) }
            .first { it.toString().contains("kotlin-stdlib") }
        Files.copy(kotlinStdlibJar, folder.resolve(kotlinStdlibJar.fileName), StandardCopyOption.REPLACE_EXISTING)
        return folder
    }

    /**
     * The fixture JAR plus two forged entries: a `.class` entry named exactly like the framework's guard
     * registry (deliberately not valid bytecode, so *defining* it would fail loudly) and a resource
     * below the framework's own resource prefix. A class loader that refuses both never even looks at
     * the bytes; one that does not would fail with a `ClassFormatError` or hand out the forged resource.
     */
    fun writeJarWithForgedFrameworkEntries(): Path {
        val folder = Files.createTempDirectory("sandbox-agent-forged-")
        val jarPath = writeFixtureJar(folder.resolve("forged.jar"))
        val forged = folder.resolve("forged-complete.jar")
        JarOutputStream(Files.newOutputStream(forged)).use { jar ->
            java.util.jar.JarInputStream(Files.newInputStream(jarPath)).use { existing ->
                var entry = existing.nextJarEntry
                while (entry != null) {
                    jar.putNextEntry(JarEntry(entry.name))
                    jar.write(existing.readBytes())
                    jar.closeEntry()
                    entry = existing.nextJarEntry
                }
            }
            jar.putNextEntry(JarEntry(FORGED_REGISTRY_ENTRY_NAME))
            jar.write("not valid bytecode".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry(FORGED_RESOURCE_ENTRY_NAME))
            jar.write("forged".toByteArray())
            jar.closeEntry()
        }
        return forged
    }

    /** Entry name of the forged guard-registry class inside [writeJarWithForgedFrameworkEntries]. */
    const val FORGED_REGISTRY_ENTRY_NAME: String = "org/pcsoft/framework/pluggiat/sandbox/agent/SandboxGuardRegistry.class"

    /** Entry name of the forged framework resource inside [writeJarWithForgedFrameworkEntries]. */
    const val FORGED_RESOURCE_ENTRY_NAME: String = "org/pcsoft/framework/pluggiat/forged-marker.txt"

    private fun writeFixtureJar(jarPath: Path): Path {
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            for (fixtureClass in listOf(SandboxAgentFixtureApi::class.java, SandboxAgentFixtureImpl::class.java)) {
                val resourceName = fixtureClass.name.replace('.', '/') + ".class"
                val bytes = requireNotNull(fixtureClass.classLoader.getResourceAsStream(resourceName)) {
                    "Compiled class resource not found: $resourceName"
                }.use { it.readBytes() }
                jar.putNextEntry(JarEntry(resourceName))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return jarPath
    }
}
