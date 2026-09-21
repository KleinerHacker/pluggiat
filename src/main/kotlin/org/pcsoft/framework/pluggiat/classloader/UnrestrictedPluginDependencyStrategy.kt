package org.pcsoft.framework.pluggiat.classloader

import java.nio.file.Path

/**
 * [PluginDependencyStrategy] that allows visibility between plugins of any two locations. The
 * framework's default strategy.
 */
class UnrestrictedPluginDependencyStrategy : PluginDependencyStrategy {
    override fun isVisible(from: Path, to: Path): Boolean = true
}
