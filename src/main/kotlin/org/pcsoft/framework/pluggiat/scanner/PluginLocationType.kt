package org.pcsoft.framework.pluggiat.scanner

/**
 * Classifies a [PluginLocation] as shipped with the host application or contributed externally.
 */
enum class PluginLocationType {
    /** The location is shipped with and controlled by the host application itself. */
    BUILTIN,

    /** The location is contributed externally, outside of the host application's control. */
    EXTERNAL,
}
