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
     *
     * Since IP-06, the exposed [ResolvedExtension.instance] is the runtime enforcement proxy over
     * the host plugin API type ([TestExporter]) rather than the concrete implementation class
     * ([CsvTestExporter]) - it still behaves like one for calls declared on the interface.
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
        assertTrue(exporters.single().instance is TestExporter)
        assertEquals("csv", (exporters.single().instance as TestExporter).name())
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

    /**
     * Use case: a plugin persisted as disabled contributes no extensions at all and is reported as
     * [PluginExtensionStatus.DISABLED]; its implementation class is never resolved/instantiated
     * (no `ExtensionMappingException` even though the class name below does not exist).
     */
    @Test
    fun `skips a disabled plugin entirely, without resolving its implementation class`() {
        val persistence = org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy(
            readCallback = { _, key -> if (key == ExtensionAggregator.ENABLED_PERSISTENCE_KEY) "false" else null },
            writeCallback = { _, _, _ -> },
        )
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry, persistenceStrategy = persistence)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry("plugin-a", "exporters", "does.not.Exist", "fileExtension" to "csv"),
        )

        val result = aggregator.aggregate(listOf(candidate))

        assertEquals(PluginExtensionStatus.DISABLED, result.pluginResults.single().status)
        assertTrue(result.extensionsByKey["exporters"].isNullOrEmpty())
    }

    /**
     * Use case: an enabled plugin (no persisted value, default enabled) contributes normally.
     */
    @Test
    fun `treats a plugin with no persisted enabled state as enabled`() {
        val persistence = org.pcsoft.framework.pluggiat.persistence.NoPersistenceStrategy()
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry, persistenceStrategy = persistence)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.CsvTestExporter",
                "fileExtension" to "csv",
            ),
        )

        val result = aggregator.aggregate(listOf(candidate))

        assertEquals(PluginExtensionStatus.LOADED, result.pluginResults.single().status)
    }

    /**
     * Use case: a runtime `UNLOAD` action on the enforcement proxy persists the plugin as disabled
     * (reason `RUNTIME_ERROR`) and invokes the candidate's [PluginExtensionCandidate.onUnload]
     * callback synchronously.
     */
    @Test
    fun `UNLOAD action persists the plugin as disabled and invokes the candidate's onUnload callback`() {
        val store = mutableMapOf<String, String>()
        val persistence = org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )
        var unloadCallbackInvoked = false
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry, persistenceStrategy = persistence)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.FailingTestExporter",
                "fileExtension" to "csv",
            ),
            onUnload = { unloadCallbackInvoked = true },
        )

        val result = aggregator.aggregate(listOf(candidate))
        val exporter = result.extensionsByKey.getValue("exporters").single().instance as TestExporter

        assertThrows(org.pcsoft.framework.pluggiat.exception.PluginFatalException::class.java) { exporter.name() }
        assertTrue(unloadCallbackInvoked)
        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
        assertEquals(ExtensionAggregator.RUNTIME_ERROR_REASON, store["plugin-a.${ExtensionAggregator.DISABLED_REASON_PERSISTENCE_KEY}"])
    }

    /**
     * Use case: an extension implementation class implementing `PluginLifecycle` has its `onLoad`
     * hook invoked before `onEnable`, in that order.
     */
    @Test
    fun `invokes PluginLifecycle hooks onLoad before onEnable on resolution`() {
        LifecycleRecordingTestExporter.callOrder.clear()
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.LifecycleRecordingTestExporter",
                "fileExtension" to "csv",
            ),
        )

        aggregator.aggregate(listOf(candidate))

        assertEquals(listOf("onLoad", "onEnable"), LifecycleRecordingTestExporter.callOrder)
    }

    /**
     * Use case: `onDisable` is invoked before `onUnload` on a runtime `UNLOAD` action, after the
     * earlier `onLoad`/`onEnable` pair from resolution.
     */
    @Test
    fun `invokes PluginLifecycle hooks onDisable before onUnload on UNLOAD`() {
        LifecycleRecordingTestExporter.callOrder.clear()
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry)
        val candidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.LifecycleRecordingTestExporter",
                "fileExtension" to "csv",
            ),
        )
        val result = aggregator.aggregate(listOf(candidate))
        val exporter = result.extensionsByKey.getValue("exporters").single().instance as TestExporter

        assertThrows(org.pcsoft.framework.pluggiat.exception.PluginFatalException::class.java) { exporter.name() }

        assertEquals(listOf("onLoad", "onEnable", "onDisable", "onUnload"), LifecycleRecordingTestExporter.callOrder)
    }

    /**
     * Use case: an `UNLOAD` action only force-disables the plugin that raised the exception -
     * sibling plugins resolved in the same [ExtensionAggregator.aggregate] call stay unaffected.
     */
    @Test
    fun `UNLOAD only force-disables the affected plugin, siblings stay unaffected`() {
        val store = mutableMapOf<String, String>()
        val persistence = org.pcsoft.framework.pluggiat.persistence.CustomPersistenceStrategy(
            readCallback = { pluginId, key -> store["$pluginId.$key"] },
            writeCallback = { pluginId, key, value -> store["$pluginId.$key"] = value },
        )
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))
        val aggregator = ExtensionAggregator(registry, persistenceStrategy = persistence)
        val failingCandidate = PluginExtensionCandidate(
            "plugin-a", Path.of("plugin-a.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-a", "exporters",
                "org.pcsoft.framework.pluggiat.extension.FailingTestExporter",
                "fileExtension" to "csv",
            ),
        )
        val healthyCandidate = PluginExtensionCandidate(
            "plugin-b", Path.of("plugin-b.jar"),
            manifestWithSingleExtensionEntry(
                "plugin-b", "exporters",
                "org.pcsoft.framework.pluggiat.extension.JsonTestExporter",
                "fileExtension" to "json",
            ),
        )

        val result = aggregator.aggregate(listOf(failingCandidate, healthyCandidate))
        val exportersByPlugin = result.extensionsByKey.getValue("exporters")
            .associateBy { it.pluginId }
            .mapValues { it.value.instance as TestExporter }

        assertThrows(org.pcsoft.framework.pluggiat.exception.PluginFatalException::class.java) { exportersByPlugin.getValue("plugin-a").name() }
        assertEquals("json", exportersByPlugin.getValue("plugin-b").name())

        assertEquals("false", store["plugin-a.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
        assertEquals(null, store["plugin-b.${ExtensionAggregator.ENABLED_PERSISTENCE_KEY}"])
    }
}
