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

import com.github.jk1.license.render.ReportRenderer

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("com.github.jk1.dependency-license-report") version "3.1.4"
    id("org.cyclonedx.bom") version "3.4.1"
    id("app.cash.licensee") version "1.14.1"
    `maven-publish`
    signing
}

group = "org.pcsoft.framework"
// A release passes the tag as -PreleaseVersion=<tag>; a local build stays on the snapshot.
version = (project.findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "6.1.3"
val jacksonVersion = "2.22.3"
val jsonSchemaValidatorVersion = "3.0.7"
val mavenArtifactVersion = "3.10.0"
val slf4jVersion = "2.0.20"
val h2Version = "2.5.250"
val byteBuddyVersion = "1.18.14"
val objenesisVersion = "3.6"
val bouncyCastleVersion = "1.86"

dependencies {
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${jacksonVersion}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${jacksonVersion}")
    implementation("com.networknt:json-schema-validator:${jsonSchemaValidatorVersion}")
    implementation("org.apache.maven:maven-artifact:${mavenArtifactVersion}")
    implementation("org.slf4j:slf4j-api:${slf4jVersion}")
    // Runtime enforcement proxy in front of extension instances (IP-06 task 6).
    implementation("net.bytebuddy:byte-buddy:${byteBuddyVersion}")
    implementation("org.objenesis:objenesis:${objenesisVersion}")
    // RFC 9580 OpenPGP parsing for OpenPgpKeyserverPublicKeyProviderStrategy (IP-08 task 4).
    implementation("org.bouncycastle:bcpg-jdk18on:${bouncyCastleVersion}")
    // bcpg-jdk18on references ASN.1 object identifiers (e.g. CryptlibObjectIdentifiers) that live
    // here, but does not declare it as a dependency itself.
    implementation("org.bouncycastle:bcutil-jdk18on:${bouncyCastleVersion}")
    // Pure ASN.1/DER encoding (ASN1Integer, ASN1Boolean, DEROctetString, DERUTF8String, DERSequence,
    // DERSet, ASN1InputStream/ASN1OutputStream) for IP-04's process-isolation IPC wire format - no
    // bcpkix/TLS functionality is used, see org.pcsoft.framework.pluggiat.sandbox.process.der.DerCodec.
    implementation("org.bouncycastle:bcprov-jdk18on:${bouncyCastleVersion}")
    // Reflective mapping of Kotlin data classes onto SandboxValue.ObjectValue for IP-04's complex-object
    // support, see org.pcsoft.framework.pluggiat.sandbox.process.SandboxTypeSupport.
    implementation(kotlin("reflect"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${junitVersion}")
    // In-memory JDBC driver used only to test DatabasePersistenceStrategy against a real database.
    testImplementation("com.h2database:h2:${h2Version}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    // Since JUnit 6 the platform launcher is no longer contributed automatically.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${slf4jVersion}")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Needed by ZipJarScanStrategyTest to verify deleteOnExit registration via java.io.DeleteOnExitHook.
    jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
}

//region Sandbox agent tests
// A second test source set for the tests that can only pass with the sandbox Java agent actually
// installed: instrumentation of plugin classes, the guard calls it injects, and a process-isolated
// subprocess (which is started with '-javaagent:<this module's JAR>' only when that JAR exists).
// They cannot live in `test`, because the regular test JVM deliberately runs *without* the agent -
// several tests there assert the un-instrumented behaviour.
val sandboxAgentTest: SourceSet = sourceSets.create("sandboxAgentTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += output + compileClasspath
}

configurations["sandboxAgentTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["sandboxAgentTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val sandboxAgentTestTask = tasks.register<Test>("sandboxAgentTest") {
    group = "verification"
    description = "Runs the sandbox tests that require the pluggiat Java agent, with -javaagent set to this module's own JAR."
    testClassesDirs = sandboxAgentTest.output.classesDirs
    // The module's own JAR replaces main's classes directory on the runtime classpath: PluginSandboxAgent
    // has to resolve *from the agent JAR*, because PluginProcessManager derives the '-javaagent' path it
    // hands to a process-isolated subprocess from that class's own code source. With the classes
    // directory there instead, there would be no agent JAR to pass - and two copies of every framework
    // class in the same JVM.
    classpath = files(tasks.jar) + sandboxAgentTest.runtimeClasspath.minus(sourceSets["main"].output)
    useJUnitPlatform()
    // The agent is this module's own JAR, so the JAR has to exist before these tests run.
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    dependsOn(tasks.jar)
    jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-javaagent:${agentJar.get().asFile.absolutePath}") },
    )
}

tasks.check {
    dependsOn(sandboxAgentTestTask)
}
//endregion

// Minimum coverage for the two packages that carry the security-relevant behaviour. A rule rather
// than a report: a change that drops coverage in `sandbox` or `security` fails the build instead of
// being noticed later, because an untested branch is exactly where an enforcement gap hides.
kover {
    reports {
        // Kover applies filters per report set, not per rule, so scoping the verification to the
        // security-relevant packages also scopes the coverage reports to them. That is the intended
        // focus here: this project's coverage gate exists for `sandbox` and `security`, and a report
        // that mixes in every other package would only dilute the number the rule below guards.
        filters {
            includes {
                packages("org.pcsoft.framework.pluggiat.sandbox", "org.pcsoft.framework.pluggiat.security")
            }
            excludes {
                // Test classes of the agent test source set and the compatibility copies of interface
                // default methods are not production behaviour and cannot be exercised by a test.
                classes("*Test", "*\$DefaultImpls")
            }
        }
        verify {
            rule("Line coverage of the sandbox and security packages") {
                bound {
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// This module's own JAR doubles as the pluggiat sandbox Java agent (IP-02): a host that configures a
// restrictive PluginSandboxPolicy starts its JVM with '-javaagent:<path-to-this-jar>' (dynamic
// attachment is deliberately not supported, see PluginSandboxAgent's KDoc).
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "org.pcsoft.framework.pluggiat.sandbox.agent.PluginSandboxAgent",
            "Agent-Class" to "org.pcsoft.framework.pluggiat.sandbox.agent.PluginSandboxAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}

licenseReport {
    outputDir = layout.buildDirectory.dir("licences").get().asFile.absolutePath
    renderers = arrayOf<ReportRenderer>(
        com.github.jk1.license.render.JsonReportRenderer(),
        com.github.jk1.license.render.SimpleHtmlReportRenderer()
    )
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("EPL-1.0")
    allowUrl("https://opensource.org/license/mit")
    allowUrl("https://www.bouncycastle.org/licence.html")
}

// Published so the artifact from `git tag` builds can be pushed to GitHub Packages by release.yml.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("pluggiat")
                description.set("A plugin management framework for JVM host applications.")
                url.set("https://github.com/KleinerHacker/pluggiat")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/KleinerHacker/pluggiat")
                    connection.set("scm:git:https://github.com/KleinerHacker/pluggiat.git")
                    developerConnection.set("scm:git:https://github.com/KleinerHacker/pluggiat.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/KleinerHacker/pluggiat")
            credentials {
                username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// Signing keys come from -PsigningKey / -PsigningPassword (in CI: secrets); a local build without
// them simply skips signing.
signing {
    val signingKey = (findProperty("signingKey") as String?) ?: System.getenv("SIGNING_KEY")
    val signingPassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
    isRequired = !signingKey.isNullOrBlank()
    if (isRequired) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}

tasks {
    //region Dokka
    register<Copy>("copyDokka") {
        group = "dokka"
        description = "Copy Dokka to MkDocs"
        from(layout.buildDirectory.dir("dokka"))
        into(File("docs/docs/dokka"))
        dependsOn("dokkaGeneratePublicationHtml")
    }

    register<Delete>("deleteDokka") {
        group = "dokka"
        description = "Delete Dokka"
        delete(File("docs/docs/dokka"))
    }
    //endregion

    //region Licencing
    register<Copy>("copyLicenceReport") {
        group = "licencing"
        description = "Copy licence report to MkDocs"
        from(layout.buildDirectory.dir("licences"))
        into(File("docs/docs/licences"))
        dependsOn("generateLicenseReport")
    }

    register<Delete>("deleteLicenceReport") {
        group = "licencing"
        description = "Delete licence report"
        delete(File("docs/docs/licences"))
    }
    //endregion

    //region MkDocs
    // mike spawns `mkdocs` as a subprocess; on Windows the Python Scripts dir (where mkdocs.exe
    // lives) is often not on PATH. Resolve it once and prepend it to PATH for the mike tasks. In CI
    // (setup-python) it is already on PATH.
    val pythonScriptsDir: String? by lazy {
        runCatching {
            providers.exec {
                commandLine("python", "-c", "import sysconfig; print(sysconfig.get_path('scripts'))")
            }.standardOutput.asText.get().trim().ifEmpty { null }
        }.getOrNull()
    }

    fun Exec.withMikePath() {
        pythonScriptsDir?.let { dir ->
            environment("PATH", dir + File.pathSeparator + System.getenv("PATH"))
        }
    }

    register<Exec>("installMkDocs") {
        group = null
        description = "Install mkdocs"
        workingDir = file("docs")
        commandLine("python", "-m", "pip", "install", "--upgrade", "mkdocs")
    }

    register<Exec>("installMkDocsMaterial") {
        group = null
        description = "Install mkdocs-material"
        workingDir = file("docs")
        commandLine("python", "-m", "pip", "install", "--upgrade", "mkdocs-material")
    }

    register<Exec>("installMkDocsI18n") {
        group = null
        description = "Install mkdocs-static-i18n for the German documentation translation"
        workingDir = file("docs")
        commandLine("python", "-m", "pip", "install", "--upgrade", "mkdocs-static-i18n")
    }

    register<Exec>("installGitHubPages") {
        group = null
        description = "Install ghp-import"
        workingDir = file("docs")
        commandLine("python", "-m", "pip", "install", "--upgrade", "ghp-import")
    }

    register<Exec>("installMike") {
        group = null
        description = "Install mike for versioned docs deployment"
        workingDir = file("docs")
        commandLine("python", "-m", "pip", "install", "--upgrade", "mike")
    }

    register("installDocs") {
        group = "MKDocs"
        description = "Install mkdocs and dependencies"
        dependsOn("installMkDocs", "installMkDocsMaterial", "installMkDocsI18n", "installGitHubPages", "installMike")
    }

    register<Exec>("runDocs") {
        group = "MKDocs"
        description = "Run mkdocs serve and open browser (no version selector - that only appears on the deployed site)"
        workingDir = file("docs")
        commandLine("python", "-m", "mkdocs", "serve", "-o", "-w", ".", "-w", "./docs")
        dependsOn("installDocs", "copyDokka", "copyLicenceReport")
        finalizedBy("deleteDokka", "deleteLicenceReport")
    }

    register<Exec>("buildDocs") {
        group = "MKDocs"
        description =
            "Build the mkdocs site into build/docs (per mkdocs.yml site_dir; no serve, no deploy) - usable as a generation test"
        workingDir = file("docs")
        // --strict fails the build on warnings (broken links, missing pages ...) so it acts as a test;
        // --clean wipes the previous output first.
        commandLine("python", "-m", "mkdocs", "build", "--clean", "--strict")
        dependsOn("installDocs", "copyDokka", "copyLicenceReport")
        finalizedBy("deleteDokka", "deleteLicenceReport")
    }

    register<Exec>("deployDocs") {
        group = "MKDocs"
        description =
            "Deploy a versioned docs snapshot via mike. Pass -PdocsVersion=<tag>; falls back to \"snapshot\" if no tag is given. Requires a pre-configured git push target."
        workingDir = file("docs")
        val ver = (project.findProperty("docsVersion") as String?)?.takeIf { it.isNotBlank() }
            ?: "snapshot"
        val setLatest = ver != "snapshot" && (project.findProperty("setLatest") as String?) != "false"
        val args = buildList {
            add("python"); add("-c"); add("from mike.driver import main; main()"); add("deploy"); add("--push")
            if (setLatest) {
                add("--update-aliases"); add(ver); add("latest")
            } else add(ver)
        }
        commandLine(args)
        withMikePath()
        dependsOn("installDocs", "copyDokka", "copyLicenceReport")
        finalizedBy("deleteDokka", "deleteLicenceReport")
    }

    register<Exec>("setDefaultDocs") {
        group = "MKDocs"
        description =
            "Set the default docs version shown at the root URL via mike (run once after the first release deploy)."
        workingDir = file("docs")
        commandLine("python", "-c", "from mike.driver import main; main()", "set-default", "--push", "latest")
        withMikePath()
        dependsOn("installDocs")
    }
    //endregion
}
