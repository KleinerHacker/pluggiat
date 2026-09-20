package org.pcsoft.framework.pluggiat.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestParserTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/$name")) { "Missing test fixture: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

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
}
