package org.pcsoft.framework.pluggiat.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression test feeding the `valid-manifest.yml` fixture through YAML parsing, JSON schema
 * validation and data class mapping in one pass, and comparing the full result against a fixed
 * reference [PluginManifest].
 */
class ManifestParserRT {

    private val expected = PluginManifest(
        id = "com.example.sample-plugin",
        name = "Sample Plugin",
        version = "1.2.3",
        minVersion = "1.0.0",
        icon = "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjwvc3ZnPg==",
        description = "A sample plugin used in tests.",
        author = Author(name = "Jane Doe", mail = "jane.doe@example.com"),
        links = Links(
            documentation = "https://example.com/docs",
            sourceCode = "https://example.com/source",
        ),
        legal = Legal(
            copyright = "Copyright (c) 2026 Jane Doe",
            license = "Apache-2.0",
        ),
        dependencies = listOf(
            PluginDependency(id = "com.example.other-plugin", required = true),
            PluginDependency(id = "com.example.optional-plugin", required = false),
        ),
        extensions = mapOf(
            "com.example.commands" to listOf(
                ExtensionEntry(implementation = "com.example.sample.SampleCommand"),
            ),
        ),
    )

    /**
     * Use case: the complete `valid-manifest.yml` fixture, once parsed, validated against the JSON
     * schema and mapped to Kotlin data classes, matches the fixed reference [PluginManifest]
     * field-for-field, including nested `author`, `links`, `legal`, `dependencies` and `extensions`.
     */
    @Test
    fun `full manifest fixture maps to the expected reference PluginManifest`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/manifests/valid-manifest.yml"))
        val manifest = stream.use { ManifestParser.parse(it) }
        assertEquals(expected, manifest)
    }
}
