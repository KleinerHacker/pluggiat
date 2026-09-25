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

import org.pcsoft.framework.pluggiat.scanner.PluginScannerTestFixtures
import java.nio.file.Path

/**
 * Builds fake plugin JARs for the class-loading tests at runtime, extending
 * [PluginScannerTestFixtures] with `dependencies:` manifest entries.
 */
object PluginLoaderTestFixtures {

    /**
     * A schema-valid manifest YAML for a plugin with the given [id], optionally declaring
     * [dependencies] as pairs of dependency id to whether it is `required`.
     */
    fun manifestYaml(id: String, dependencies: List<Pair<String, Boolean>> = emptyList()): String {
        val base = PluginScannerTestFixtures.validManifestYaml(id)
        if (dependencies.isEmpty()) return base
        val dependenciesYaml = buildString {
            appendLine("dependencies:")
            for ((dependencyId, required) in dependencies) {
                appendLine("  - id: $dependencyId")
                append("    required: $required")
            }
        }
        return "$base\n$dependenciesYaml"
    }

    /**
     * Writes a single-JAR plugin candidate at [path] with the given [id] and [dependencies].
     */
    fun writeSingleJarPlugin(path: Path, id: String, dependencies: List<Pair<String, Boolean>> = emptyList()) {
        PluginScannerTestFixtures.writeJar(path, mapOf("META-INF/plugin.yml" to manifestYaml(id, dependencies)))
    }
}
