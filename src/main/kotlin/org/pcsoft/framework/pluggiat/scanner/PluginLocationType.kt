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
