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

package org.pcsoft.framework.pluggiat.security

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PublicKey

/**
 * Signs JARs for the signature-strategy tests using the JDK's own `keytool`/`jarsigner` command
 * line tools (shipped with every JDK under `$JAVA_HOME/bin`), so no third-party signing library is
 * needed as a test dependency.
 */
object SignatureTestFixtures {
    private const val STORE_PASSWORD = "changeit"

    /**
     * Generates a new self-signed key pair under [alias] into a new keystore file inside [dir].
     */
    fun generateSelfSignedKeystore(dir: Path, alias: String): Path {
        val keystorePath = dir.resolve("$alias-keystore.jks")
        runJdkTool(
            "keytool",
            "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-keystore", keystorePath.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
            "-dname", "CN=Plugin Test Signer $alias, OU=Test, O=Test, L=Test, ST=Test, C=DE",
        )
        return keystorePath
    }

    /**
     * Generates a new self-signed key pair under [alias] into a new keystore file inside [dir],
     * with a certificate validity period that already lies entirely in the past (started 2 years
     * ago, valid for 30 days) - for testing that an expired signing certificate is rejected.
     */
    fun generateExpiredSelfSignedKeystore(dir: Path, alias: String): Path {
        val keystorePath = dir.resolve("$alias-keystore.jks")
        runJdkTool(
            "keytool",
            "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
            "-startdate", "-2y", "-validity", "30",
            "-keystore", keystorePath.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
            "-dname", "CN=Plugin Test Signer $alias, OU=Test, O=Test, L=Test, ST=Test, C=DE",
        )
        return keystorePath
    }

    /**
     * Signs [jarPath] in place using the key pair under [alias] in [keystorePath].
     */
    fun signJar(jarPath: Path, keystorePath: Path, alias: String) {
        runJdkTool(
            "jarsigner",
            "-keystore", keystorePath.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
            jarPath.toString(), alias,
        )
    }

    /**
     * Reads the public key of [alias]'s certificate out of [keystorePath].
     */
    fun readPublicKey(keystorePath: Path, alias: String): PublicKey {
        val keyStore = KeyStore.getInstance("JKS")
        Files.newInputStream(keystorePath).use { keyStore.load(it, STORE_PASSWORD.toCharArray()) }
        return keyStore.getCertificate(alias).publicKey
    }

    private fun runJdkTool(toolName: String, vararg args: String) {
        val javaHome = System.getProperty("java.home")
        val executableName = if (System.getProperty("os.name").lowercase().contains("win")) "$toolName.exe" else toolName
        val executable = Path.of(javaHome, "bin", executableName).toString()
        val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "JDK tool '$toolName' failed with exit code $exitCode: $output" }
    }
}
