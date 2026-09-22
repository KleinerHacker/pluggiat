package org.pcsoft.framework.pluggiat.classloader

import java.nio.file.Path

/**
 * [PluginDependencyStrategy] that, in addition to a plugin location always seeing itself, allows
 * visibility onto the explicitly configured [allowedLocations].
 *
 * @property allowedLocations locations a plugin location may additionally see, besides itself
 */
class LocationPluginDependencyStrategy(
    private val allowedLocations: Set<Path>,
) : PluginDependencyStrategy {
    override fun isVisible(from: Path, to: Path): Boolean = from == to || to in allowedLocations
}
