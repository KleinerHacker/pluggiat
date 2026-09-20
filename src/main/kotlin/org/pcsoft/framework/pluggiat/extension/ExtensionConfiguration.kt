package org.pcsoft.framework.pluggiat.extension

import kotlin.reflect.KClass

/**
 * Base interface for the host-defined configuration of a single extension point.
 *
 * A concrete implementation of this interface represents one extension point (e.g. "exporters") and
 * must be annotated with [ExtensionPoint]. Plugin developers never implement or reference this
 * interface directly; they only implement the host plugin API interface [T].
 *
 * @param T the host plugin API interface this extension point contributes implementations for
 * @property implementation the concrete plugin implementation class contributed by a plugin,
 * resolved from the manifest entry's `implementation` field
 */
interface ExtensionConfiguration<T : Any> {
    val implementation: KClass<out T>
}
