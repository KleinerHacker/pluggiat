package org.pcsoft.framework.pluggiat.classloader

import java.nio.file.Path

/**
 * [PluginDependencyStrategy] that disallows plugin dependencies altogether, including between
 * plugins of the same location.
 */
class DisallowPluginDependencyStrategy : PluginDependencyStrategy {
    override fun isVisible(from: Path, to: Path): Boolean = false
}
