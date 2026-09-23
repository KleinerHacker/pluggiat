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
