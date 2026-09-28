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

package org.pcsoft.framework.pluggiat.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestParserTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/$name")) { "Missing test fixture: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun manifestYaml(icon: String = "aWNvbg==", legalBlock: String = ""): String = """
        ${'$'}version: 1
        id: com.example.advisory-plugin
        name: Advisory Plugin
        version: "1.0.0"
        minVersion: "1.0.0"
        icon: "$icon"
    """.trimIndent() + legalBlock

    /**
     * Use case: a minimal manifest containing only the required fields is parsed successfully and
     * every optional field defaults to its documented empty/null value.
     */
    @Test
    fun `parses a manifest with only required fields`() {
        val yaml = """
            ${'$'}version: 1
            id: com.example.minimal-plugin
            name: Minimal Plugin
            version: "1.0.0"
            minVersion: "1.0.0"
            icon: aWNvbg==
        """.trimIndent()

        val manifest = ManifestParser.parse(yaml)

        assertEquals("com.example.minimal-plugin", manifest.id)
        assertEquals("Minimal Plugin", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals("1.0.0", manifest.minVersion)
        assertEquals("aWNvbg==", manifest.icon)
        assertEquals(null, manifest.description)
        assertEquals(null, manifest.author)
        assertEquals(null, manifest.links)
        assertEquals(null, manifest.legal)
        assertEquals(emptyList<PluginDependency>(), manifest.dependencies)
        assertEquals(emptyMap<String, List<ExtensionEntry>>(), manifest.extensions)
    }

    /**
     * Use case: the internal `$version` migration field is read without error but is not exposed on
     * the resulting [PluginManifest].
     */
    @Test
    fun `dollar-version field is accepted but not exposed on PluginManifest`() {
        val manifest = ManifestParser.parse(resource("valid-manifest.yml"))
        assertTrue(PluginManifest::class.java.declaredFields.none { it.name == "\$version" })
        assertEquals("com.example.sample-plugin", manifest.id)
    }

    /**
     * Use case: a manifest missing a required field (`icon`) fails validation with a
     * [ManifestValidationException] describing the violation, instead of silently producing an
     * incomplete manifest.
     */
    @Test
    fun `fails validation when a required field is missing`() {
        val exception = assertThrows(ManifestValidationException::class.java) {
            ManifestParser.parse(resource("invalid-manifest-missing-icon.yml"))
        }
        assertTrue(exception.violations.isNotEmpty())
    }

    /**
     * Use case: content that is not valid YAML/JSON at all is reported as a
     * [ManifestValidationException] rather than propagating a raw parser exception.
     */
    @Test
    fun `fails for content that is not valid YAML`() {
        assertThrows(ManifestValidationException::class.java) {
            ManifestParser.parse(":: not: [valid")
        }
    }

    /**
     * Use case: the manifest scanner file name convention is exposed so the future scanner
     * implementation (IP-03) can look up either supported manifest file name.
     */
    @Test
    fun `exposes both supported manifest file names`() {
        assertEquals(listOf("META-INF/plugin.yml", "META-INF/plugin.yaml"), ManifestParser.MANIFEST_FILE_NAMES)
    }

    /**
     * Use case: an icon whose content is valid Base64 but no recognisable image only produces a
     * warning - the manifest is still accepted and the icon is kept as declared.
     */
    @Test
    fun `an unrecognised icon format does not invalidate the manifest`() {
        val manifest = ManifestParser.parse(manifestYaml(icon = "aWNvbg=="))

        assertEquals("aWNvbg==", manifest.icon)
    }

    /**
     * Use case: an icon that is not Base64 at all only produces a warning as well and never rejects the
     * manifest, since the icon check is best effort.
     */
    @Test
    fun `an icon that is not valid Base64 does not invalidate the manifest`() {
        val manifest = ManifestParser.parse(manifestYaml(icon = "not-base64!!!"))

        assertEquals("not-base64!!!", manifest.icon)
    }

    /**
     * Use case: a license that is not an SPDX expression only produces a warning; the free-form value
     * is preserved on the parsed manifest.
     */
    @Test
    fun `an unknown license does not invalidate the manifest`() {
        val manifest = ManifestParser.parse(manifestYaml(legalBlock = "\nlegal:\n  license: My Custom License 1.0"))

        assertEquals("My Custom License 1.0", manifest.legal?.license)
    }

    /**
     * Use case: an SPDX license expression with operators is accepted and preserved unchanged.
     */
    @Test
    fun `an SPDX license expression is accepted`() {
        val manifest = ManifestParser.parse(manifestYaml(legalBlock = "\nlegal:\n  license: Apache-2.0 OR MIT"))

        assertEquals("Apache-2.0 OR MIT", manifest.legal?.license)
    }

    /**
     * Use case: a license value containing control characters (an attempt to forge log lines) is still
     * parsed without error; it is only shortened and sanitised when logged.
     */
    @Test
    fun `a license containing control characters does not invalidate the manifest`() {
        val manifest = ManifestParser.parse(manifestYaml(legalBlock = "\nlegal:\n  license: \"Fake\\nERROR forged line\""))

        assertEquals("Fake\nERROR forged line", manifest.legal?.license)
    }
}
