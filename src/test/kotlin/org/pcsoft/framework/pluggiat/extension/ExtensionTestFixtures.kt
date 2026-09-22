package org.pcsoft.framework.pluggiat.extension

import kotlin.reflect.KClass

/**
 * Fixtures shared by the extension-point tests: a host plugin API interface, host-defined
 * extension configurations, and plugin implementation classes for both a non-exclusive and an
 * exclusive extension point.
 */
interface TestExporter {
    fun name(): String
}

@ExtensionPoint(key = "exporters")
data class ExporterTestConfig(
    override val implementation: KClass<out TestExporter>,
    val fileExtension: String,
) : ExtensionConfiguration<TestExporter>

class CsvTestExporter : TestExporter {
    override fun name(): String = "csv"
}

class JsonTestExporter : TestExporter {
    override fun name(): String = "json"
}

class FailingTestExporter : TestExporter {
    override fun name(): String = throw IllegalStateException("boom")
}

/** Records the order [org.pcsoft.framework.pluggiat.PluginLifecycle] hooks were invoked in. */
class LifecycleRecordingTestExporter : TestExporter, org.pcsoft.framework.pluggiat.PluginLifecycle {
    companion object {
        val callOrder: MutableList<String> = mutableListOf()
    }

    override fun name(): String = throw IllegalStateException("boom")
    override fun onLoad() {
        callOrder += "onLoad"
    }

    override fun onEnable() {
        callOrder += "onEnable"
    }

    override fun onDisable() {
        callOrder += "onDisable"
    }

    override fun onUnload() {
        callOrder += "onUnload"
    }
}

interface TestSlot

@ExtensionPoint(key = "singleton-slot", exclusive = true)
data class SlotTestConfig(
    override val implementation: KClass<out TestSlot>,
) : ExtensionConfiguration<TestSlot>

class TestSlotImplA : TestSlot
class TestSlotImplB : TestSlot

class NotAnnotatedTestConfig(
    override val implementation: KClass<out TestExporter>,
) : ExtensionConfiguration<TestExporter>

/** A final (default Kotlin visibility) host plugin API type, deliberately not proxy-eligible. */
class FinalTestApi

@ExtensionPoint(key = "final-api")
data class FinalApiTestConfig(
    override val implementation: KClass<out FinalTestApi>,
) : ExtensionConfiguration<FinalTestApi>
