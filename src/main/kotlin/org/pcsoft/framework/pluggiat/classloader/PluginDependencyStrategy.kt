package org.pcsoft.framework.pluggiat.classloader

import java.nio.file.Path

/**
 * Decides whether plugins loaded from one location may declare visible dependencies on plugins
 * loaded from another location, as part of a location's dependency-visibility configuration
 * (global default, optionally overridden per location).
 *
 * A plugin location is always allowed to see other plugins within itself ([from] == [to]),
 * regardless of the configured strategy - the only way to suppress even same-location
 * dependencies is [DisallowPluginDependencyStrategy].
 */
interface PluginDependencyStrategy {
    /**
     * Whether a plugin loaded from location [from] may see (declare a dependency on) a plugin
     * loaded from location [to].
     */
    fun isVisible(from: Path, to: Path): Boolean
}
