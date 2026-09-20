package org.pcsoft.framework.pluggiat.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.ManifestParser
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import java.nio.file.Path

class ExtensionAggregatorTest {

    private fun baseManifestYaml(id: String): StringBuilder = StringBuilder()
        .appendLine("\$version: 1")
        .appendLine("id: $id")
        .appendLine("name: $id")
        .appendLine("version: \"1.0.0\"")
        .appendLine("minVersion: \"1.0.0\"")
        .appendLine("icon: aWNvbg==")

    private fun manifestWithoutExtensions(id: String): PluginManifest =
        ManifestParser.parse(baseManifestYaml(id).toString())

    private fun manifestWithSingleExtensionEntry(
        id: String,
        key: String,
        implementation: String,
        vararg additionalFields: Pair<String, String>,
    ): PluginManifest {
        val yaml = baseManifestYaml(id).apply {
            appendLine("extensions:")
            appendLine("  $key:")
            appendLine("    - implementation: $implementation")
            for ((fieldName, fieldValue) in additionalFields) {
                appendLine("      $fieldName: $fieldValue")
            }
        }
        return ManifestParser.parse(yaml.toString())
    }

    /**
     * Use case: a single plugin's extension entry is resolved to its registered configuration
     * class, mapped with its extra YAML fields, and its implementation class is instantiated.
     */
    @Test
    fun `maps and instantiates a simple extension entry`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidate = PluginExtensionCandidate(
            pluginId = "plugin-a",
            path = Path.of("plugin-a.jar"),
            manifest = manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.CsvTestExporter",
                "fileExtension" to "csv",
            ),
        )

        val result = aggregator.aggregate(listOf(candidate))

        val exporters = result.extensionsByKey.getValue("exporters")
        assertEquals(1, exporters.size)
        val config = exporters.single().configuration as ExporterTestConfig
        assertEquals("csv", config.fileExtension)
        assertEquals(CsvTestExporter::class, config.implementation)
        assertTrue(exporters.single().instance is CsvTestExporter)
        assertEquals(PluginExtensionStatus.LOADED, result.pluginResults.single().status)
    }

    /**
     * Use case: two plugins contribute to the same non-exclusive extension point key; both entries
     * end up in one combined list without any conflict handling.
     */
    @Test
    fun `combines entries of multiple plugins for a non-exclusive key`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidateA = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.CsvTestExporter",
                "fileExtension" to "csv",
            ),
        )
        val candidateB = PluginExtensionCandidate(
            "plugin-b", Path.of("plugin-b.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-b", "exporters",
                "org.pcsoft.framework.pluggiat.extension.JsonTestExporter",
                "fileExtension" to "json",
            ),
        )

        val result = aggregator.aggregate(listOf(candidateA, candidateB))

        assertEquals(2, result.extensionsByKey.getValue("exporters").size)
        assertTrue(result.pluginResults.all { it.status == PluginExtensionStatus.LOADED })
    }

    /**
     * Use case: two plugins contribute to the same exclusive extension point key; both plugins are
     * rejected entirely and neither contributes to the aggregated result.
     */
    @Test
    fun `rejects both plugins on an exclusive key conflict`() {
        val registry = ExtensionPointRegistry(listOf(SlotTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidateA = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "singleton-slot",
                "org.pcsoft.framework.pluggiat.extension.TestSlotImplA",
            ),
        )
        val candidateB = PluginExtensionCandidate(
            "plugin-b", Path.of("plugin-b.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-b", "singleton-slot",
                "org.pcsoft.framework.pluggiat.extension.TestSlotImplB",
            ),
        )

        val result = aggregator.aggregate(listOf(candidateA, candidateB))

        assertTrue(result.pluginResults.all { it.status == PluginExtensionStatus.REJECTED_EXCLUSIVE_CONFLICT })
        assertTrue(result.extensionsByKey["singleton-slot"].isNullOrEmpty())
    }

    /**
     * Use case: the [java.nio.file.Path] passed in via [PluginExtensionCandidate] is returned
     * unchanged in the corresponding [PluginExtensionResult], since IP-02 has no knowledge of the
     * plugin's actual location on disk.
     */
    @Test
    fun `passes the candidate path through unchanged`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val path = Path.of("some", "plugin-a.jar")
        val candidate = PluginExtensionCandidate("plugin-a", path, manifestWithoutExtensions("plugin-a"))

        val result = aggregator.aggregate(listOf(candidate))

        assertEquals(path, result.pluginResults.single().path)
    }

    /**
     * Use case: a manifest contributes to an extension point key for which no configuration class
     * has been registered by the host; this is reported as a mapping error instead of being
     * silently ignored.
     */
    @Test
    fun `fails when the extension entry key is not registered`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "unknown-key",
                "org.pcsoft.framework.pluggiat.extension.CsvTestExporter",
            ),
        )

        assertThrows(ExtensionMappingException::class.java) {
            aggregator.aggregate(listOf(candidate))
        }
    }
}
