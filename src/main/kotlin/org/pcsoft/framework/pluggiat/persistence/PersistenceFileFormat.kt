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

package org.pcsoft.framework.pluggiat.persistence

/**
 * File format used by [FilePersistenceStrategy] to store its key-value state.
 */
enum class PersistenceFileFormat {
    /** `java.util.Properties` text format, keys flattened as `"<pluginId>.<key>"`. */
    PROPERTIES,

    /** JSON, nested as `{ "<pluginId>": { "<key>": "<value>" } }`. */
    JSON,

    /** YAML, nested the same way as [JSON]. */
    YAML,

    /** `java.util.Properties` XML format (`Properties.storeToXML`/`loadFromXML`), same key flattening as [PROPERTIES]. */
    XML,
}
