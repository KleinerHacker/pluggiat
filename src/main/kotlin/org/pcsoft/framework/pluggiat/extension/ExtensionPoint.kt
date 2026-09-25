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
