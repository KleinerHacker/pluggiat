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

package org.pcsoft.framework.pluggiat.sandbox.process.fixture

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the already-compiled process isolation fixture classes (found via the test classpath, exactly
 * as Gradle compiled them) into the two candidate shapes a process-isolated plugin can have: a single
 * JAR and a ZIP of JARs.
 *
 * Both shapes carry the Kotlin standard library: the fixture classes' compiler-generated null-check
 * intrinsics need it at runtime, and the subprocess has an empty SDK whitelist, so it cannot borrow it
 * from the host's own application classpath - a process-isolated plugin has to be self-contained.
 */
object ProcessIsolationFixturePackaging {

    private val fixtureClasses = listOf(
        ProcessIsolationFixtureApi::class.java,
        ProcessIsolationFixtureImpl::class.java,
        FixturePerson::class.java,
    )

    /**
     * Writes one JAR that contains the fixture classes and, merged in, every class and resource of the
     * Kotlin standard library (its `META-INF` metadata excluded), and returns its path.
     */
    fun writeSingleJar(): Path {
        val jarPath = Files.createTempDirectory("process-isolation-fixture-jar-").resolve("fixture-with-stdlib.jar")
        val written = mutableSetOf<String>()
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            writeFixtureEntries(jar, written)
            JarInputStream(Files.newInputStream(kotlinStdlibJar())).use { stdlib ->
                var entry = stdlib.nextJarEntry
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.startsWith("META-INF/") && written.add(entry.name)) {
                        jar.putNextEntry(JarEntry(entry.name))
                        jar.write(stdlib.readBytes())
                        jar.closeEntry()
                    }
                    entry = stdlib.nextJarEntry
                }
            }
        }
        return jarPath
    }

    /**
     * Writes one ZIP that contains the fixture JAR and a copy of the Kotlin standard library JAR side by
     * side - the layout of a `ZipJarScanStrategy` candidate - and returns its path.
     */
    fun writeZip(): Path {
        val folder = Files.createTempDirectory("process-isolation-fixture-zip-")
        val fixtureJar = folder.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(fixtureJar)).use { writeFixtureEntries(it, mutableSetOf()) }
        val stdlibJar = kotlinStdlibJar()
        val zipPath = folder.resolve("fixture-plugin.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry("fixture.jar"))
            zip.write(Files.readAllBytes(fixtureJar))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(stdlibJar.fileName.toString()))
            zip.write(Files.readAllBytes(stdlibJar))
            zip.closeEntry()
        }
        return zipPath
    }

    private fun writeFixtureEntries(jar: JarOutputStream, written: MutableSet<String>) {
        for (fixtureClass in fixtureClasses) {
            val resourceName = fixtureClass.name.replace('.', '/') + ".class"
            val bytes = requireNotNull(fixtureClass.classLoader.getResourceAsStream(resourceName)) {
                "Compiled class resource not found: $resourceName"
            }.use { it.readBytes() }
            jar.putNextEntry(JarEntry(resourceName))
            jar.write(bytes)
            jar.closeEntry()
            written += resourceName
        }
    }

    private fun kotlinStdlibJar(): Path =
        System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { Path.of(it) }
            .first { it.toString().contains("kotlin-stdlib") }
}
