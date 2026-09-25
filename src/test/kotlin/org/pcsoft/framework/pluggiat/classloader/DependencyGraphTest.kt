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

package org.pcsoft.framework.pluggiat.classloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.PluginDependency
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import java.nio.file.Path

/**
 * Developer tests for [DependencyGraph.topologicalOrder]: cycle detection and load-order
 * correctness for a simple dependency DAG.
 */
class DependencyGraphTest {

    private val singleLocation = Path.of("/plugins")
    private val locationOf: (PluginManifest) -> Path = { singleLocation }
    private val unrestricted: (Path) -> PluginDependencyStrategy = { UnrestrictedPluginDependencyStrategy() }

    private fun manifest(id: String, vararg dependencies: PluginDependency) = PluginManifest(
        id = id, name = id, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==",
        dependencies = dependencies.toList(),
    )

    /**
     * Two plugins declaring a `required` dependency on each other form a cycle and must be
     * rejected with a [CyclicDependencyException] naming both plugin ids.
     */
    @Test
    fun `cyclic dependency is detected and rejected`() {
        val a = manifest("a", PluginDependency("b", required = true))
        val b = manifest("b", PluginDependency("a", required = true))

        val exception = assertThrows(CyclicDependencyException::class.java) {
            DependencyGraph.topologicalOrder(listOf(a, b), locationOf, unrestricted)
        }

        assertTrue(exception.cycle.containsAll(listOf("a", "b")))
    }

    /**
     * For a simple DAG where both `a` and `c` require `b`, the resulting order must place `b`
     * before both of its dependents.
     */
    @Test
    fun `topological order respects a simple DAG`() {
        val a = manifest("a", PluginDependency("b", required = true))
        val b = manifest("b")
        val c = manifest("c", PluginDependency("b", required = true))

        val order = DependencyGraph.topologicalOrder(listOf(a, b, c), locationOf, unrestricted)

        val ids = order.map { it.id }
        assertTrue(ids.indexOf("b") < ids.indexOf("a"))
        assertTrue(ids.indexOf("b") < ids.indexOf("c"))
        assertEquals(3, ids.size)
    }
}
