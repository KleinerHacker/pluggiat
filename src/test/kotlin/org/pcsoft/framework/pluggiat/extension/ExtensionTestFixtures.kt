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
