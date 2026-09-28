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

package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.PluginLifecycle
import org.pcsoft.framework.pluggiat.PluginManager
import org.pcsoft.framework.pluggiat.extension.ExporterTestConfig
import org.pcsoft.framework.pluggiat.extension.SlotTestConfig
import org.pcsoft.framework.pluggiat.extension.TestExporter
import org.pcsoft.framework.pluggiat.extension.TestSlot
import org.pcsoft.framework.pluggiat.pluginManager
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.SandboxViolation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import org.pcsoft.framework.pluggiat.security.InsecureSecurityStrategy
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared record of every lifecycle hook invocation made by the fixtures of [ReaggregationLifecycleTest],
 * in call order, formatted as `<label>:<hook>`.
 */
object ReaggregationEvents {
    val events: MutableList<String> = CopyOnWriteArrayList()
}

/** Base of the fixtures below: records each [PluginLifecycle] hook under its own label. */
abstract class RecordingLifecycle(private val label: String) : PluginLifecycle {
    override fun onLoad() {
        ReaggregationEvents.events += "$label:onLoad"
    }

    override fun onEnable() {
        ReaggregationEvents.events += "$label:onEnable"
    }

    override fun onDisable() {
        ReaggregationEvents.events += "$label:onDisable"
    }

    override fun onUnload() {
        ReaggregationEvents.events += "$label:onUnload"
    }
}

class ReaggregationExporterA : RecordingLifecycle("A"), TestExporter {
    override fun name(): String = "a"
}

class ReaggregationExporterB : RecordingLifecycle("B"), TestExporter {
    override fun name(): String = "b"
}

class ReaggregationFailingExporter : RecordingLifecycle("F"), TestExporter {
    override fun name(): String = throw IllegalStateException("boom")
}

class ReaggregationSlotA : RecordingLifecycle("SA"), TestSlot

class ReaggregationSlotB : RecordingLifecycle("SB"), TestSlot

/**
 * Verifies that [PluginManager] ends the lifecycle of the previous extension instances before every
 * re-aggregation (`scan`, `reload`, `unload`, `forceLoad`), and that a plugin already finished by
 * another path (a regular unload, a runtime `UNLOAD`, a forced sandbox unload) is not torn down twice.
 */
class ReaggregationLifecycleTest {

    private val fixturePackage = "org.pcsoft.framework.pluggiat.orchestration"

    @BeforeEach
    fun clearEvents() {
        ReaggregationEvents.events.clear()
    }

    private fun writePlugin(tempDir: Path, id: String, key: String, implementation: String) {
        val extraFields = if (key == "exporters") "\n                  fileExtension: csv" else ""
        val manifest = """
            ${'$'}version: 1
            id: $id
            name: $id
            version: "1.0.0"
            minVersion: "1.0.0"
            icon: aWNvbg==
            extensions:
              $key:
                - implementation: $fixturePackage.$implementation$extraFields
        """.trimIndent()
        PluginScannerTestFixtures.writeJar(tempDir.resolve("$id.jar"), mapOf("META-INF/plugin.yml" to manifest))
    }

    private fun managerFor(tempDir: Path): PluginManager = pluginManager {
        defaultSecurityChain {
            type = PluginLocationType.EXTERNAL
            addStrategy(InsecureSecurityStrategy())
        }
        sdkWhitelistEntry {
            packageName = "org.pcsoft.framework.pluggiat"
        }
        extensionPoint(ExporterTestConfig::class)
        extensionPoint(SlotTestConfig::class)
        location {
            path = tempDir
            type = PluginLocationType.EXTERNAL
            scanStrategy = SingleJarScanStrategy()
        }
    }

    /**
     * Use case: a second `scan` re-instantiates every plugin, so the instances of the first scan first
     * receive `onDisable` and `onUnload`, and only then do the new instances receive `onLoad` and
     * `onEnable` - no instance is left running without having been unloaded.
     */
    @Test
    fun `a rescan tears down the previous instances before instantiating the plugins again`(@TempDir tempDir: Path) {
        writePlugin(tempDir, "plugin-a", "exporters", "ReaggregationExporterA")
        val manager = managerFor(tempDir)

        manager.scan()
        assertEquals(listOf("A:onLoad", "A:onEnable"), ReaggregationEvents.events.toList())

        manager.scan()

        assertEquals(
            listOf("A:onLoad", "A:onEnable", "A:onDisable", "A:onUnload", "A:onLoad", "A:onEnable"),
            ReaggregationEvents.events.toList(),
        )
    }

    /**
     * Use case: unloading one plugin runs its own hooks exactly once, while the remaining plugin is
     * torn down and instantiated again by the re-aggregation that follows.
     */
    @Test
    fun `unload runs the hooks of the unloaded plugin once and re-instantiates the others`(@TempDir tempDir: Path) {
        writePlugin(tempDir, "plugin-a", "exporters", "ReaggregationExporterA")
        writePlugin(tempDir, "plugin-b", "exporters", "ReaggregationExporterB")
        val manager = managerFor(tempDir)
        manager.scan()
        ReaggregationEvents.events.clear()

        manager.unload("plugin-a")

        val events = ReaggregationEvents.events.toList()
        assertEquals(1, events.count { it == "A:onDisable" })
        assertEquals(1, events.count { it == "A:onUnload" })
        assertEquals(0, events.count { it == "A:onLoad" })
        assertEquals(
            listOf("B:onDisable", "B:onUnload", "B:onLoad", "B:onEnable"),
            events.filter { it.startsWith("B:") },
        )
    }

    /**
     * Use case: a plugin deactivated by a runtime `UNLOAD` action already ran its `onDisable` and
     * `onUnload`, so the next re-aggregation must not invoke them a second time.
     */
    @Test
    fun `a plugin unloaded at runtime is not torn down again by the next re-aggregation`(@TempDir tempDir: Path) {
        writePlugin(tempDir, "plugin-f", "exporters", "ReaggregationFailingExporter")
        writePlugin(tempDir, "plugin-b", "exporters", "ReaggregationExporterB")
        val manager = managerFor(tempDir)
        manager.scan()
        manager.getExtensions<TestExporter>("exporters").forEach { runCatching { it.name() } }
        assertEquals(1, ReaggregationEvents.events.count { it == "F:onDisable" })

        manager.unload("plugin-b")

        assertEquals(1, ReaggregationEvents.events.count { it == "F:onDisable" })
        assertEquals(1, ReaggregationEvents.events.count { it == "F:onUnload" })
    }

    /**
     * Use case: a plugin forcibly unloaded after a sandbox violation never gets another lifecycle hook,
     * not even during the re-aggregation the violation triggers - it is not trusted to run more code.
     */
    @Test
    fun `a plugin unloaded after a sandbox violation receives no lifecycle hooks`(@TempDir tempDir: Path) {
        writePlugin(tempDir, "plugin-a", "exporters", "ReaggregationExporterA")
        writePlugin(tempDir, "plugin-b", "exporters", "ReaggregationExporterB")
        val manager = managerFor(tempDir)
        manager.scan()
        ReaggregationEvents.events.clear()

        manager.sandbox.reportViolation("plugin-a", SandboxViolation("plugin-a", SandboxApiCategory.NETWORK, "blocked network access"))

        val events = ReaggregationEvents.events.toList()
        assertEquals(emptyList<String>(), events.filter { it.startsWith("A:") })
        assertEquals(
            listOf("B:onDisable", "B:onUnload", "B:onLoad", "B:onEnable"),
            events.filter { it.startsWith("B:") },
        )
    }

    /**
     * Use case: two plugins rejected by an exclusive extension point conflict were still instantiated
     * and had `onLoad`/`onEnable` invoked, so the next re-aggregation must end their lifecycle too.
     */
    @Test
    fun `instances of plugins rejected by an exclusive conflict are torn down as well`(@TempDir tempDir: Path) {
        writePlugin(tempDir, "plugin-a", "singleton-slot", "ReaggregationSlotA")
        writePlugin(tempDir, "plugin-b", "singleton-slot", "ReaggregationSlotB")
        val manager = managerFor(tempDir)
        manager.scan()
        assertEquals(1, ReaggregationEvents.events.count { it == "SA:onEnable" })
        assertEquals(1, ReaggregationEvents.events.count { it == "SB:onEnable" })

        manager.scan()

        assertEquals(1, ReaggregationEvents.events.count { it == "SA:onDisable" })
        assertEquals(1, ReaggregationEvents.events.count { it == "SA:onUnload" })
        assertEquals(1, ReaggregationEvents.events.count { it == "SB:onDisable" })
        assertEquals(1, ReaggregationEvents.events.count { it == "SB:onUnload" })
    }
}
