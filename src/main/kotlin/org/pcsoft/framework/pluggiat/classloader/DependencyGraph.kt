package org.pcsoft.framework.pluggiat.classloader

import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import java.nio.file.Path

/**
 * Thrown by [DependencyGraph.topologicalOrder] when [cycle] of plugin ids forms a dependency
 * cycle (a `required` or `optional` dependency alike).
 */
class CyclicDependencyException(val cycle: List<String>) :
    Exception("Cyclic plugin dependency: ${cycle.joinToString(" -> ")}")

/**
 * Computes a load order for a set of plugin manifests that respects their declared
 * `required`/`optional` dependencies, and detects dependency cycles.
 */
internal object DependencyGraph {

    /**
     * Returns [manifests] in an order where every plugin appears after every other plugin of
     * [manifests] it visibly depends on (see [dependencyStrategyOf]). A dependency on a plugin id
     * not present in [manifests], or not visible per [dependencyStrategyOf], is treated as if the
     * dependency did not exist at all - it never participates in cycle detection or ordering.
     *
     * @throws CyclicDependencyException if [manifests] contain a dependency cycle
     */
    fun topologicalOrder(
        manifests: List<PluginManifest>,
        locationOf: (PluginManifest) -> Path,
        dependencyStrategyOf: (Path) -> PluginDependencyStrategy,
    ): List<PluginManifest> {
        val byId = manifests.associateBy { it.id }

        fun visibleDependenciesOf(manifest: PluginManifest): List<PluginManifest> {
            val fromLocation = locationOf(manifest)
            val strategy = dependencyStrategyOf(fromLocation)
            return manifest.dependencies.mapNotNull { dependency ->
                val target = byId[dependency.id] ?: return@mapNotNull null
                val toLocation = locationOf(target)
                target.takeIf { strategy.isVisible(fromLocation, toLocation) }
            }
        }

        val result = mutableListOf<PluginManifest>()
        val visited = HashMap<String, Boolean>() // absent=white, false=gray, true=black
        val path = mutableListOf<String>()

        fun visit(manifest: PluginManifest) {
            when (visited[manifest.id]) {
                true -> return
                false -> {
                    val cycleStart = path.indexOf(manifest.id)
                    throw CyclicDependencyException(path.subList(cycleStart, path.size) + manifest.id)
                }

                null -> {
                    visited[manifest.id] = false
                    path.add(manifest.id)
                    visibleDependenciesOf(manifest).forEach(::visit)
                    path.removeAt(path.size - 1)
                    visited[manifest.id] = true
                    result.add(manifest)
                }
            }
        }

        manifests.forEach(::visit)
        return result
    }
}
