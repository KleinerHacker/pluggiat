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
