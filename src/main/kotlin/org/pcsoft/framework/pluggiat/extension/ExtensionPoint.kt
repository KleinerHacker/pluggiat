package org.pcsoft.framework.pluggiat.extension

/**
 * Marks a host-defined [ExtensionConfiguration] class as the configuration for one extension point.
 *
 * @property key the `extensions.<key>[]` key in the plugin manifest this extension point is declared under
 * @property exclusive if `true`, at most one plugin may contribute an entry for [key] across all
 * scanned plugins; if two plugins do, both are rejected entirely
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExtensionPoint(
    val key: String,
    val exclusive: Boolean = false,
)
