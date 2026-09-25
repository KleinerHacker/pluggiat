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

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [PluginPersistenceStrategy]: reads always resolve to `null`, writes are a no-op.
 *
 * Not recommended for production software - any framework state that relies on persistence (e.g.
 * the checksum security strategy, or the plugin enabled/disabled status) effectively resets on
 * every host application start when this strategy is used.
 */
class NoPersistenceStrategy : PluginPersistenceStrategy {
    private val logger = LoggerFactory.getLogger(NoPersistenceStrategy::class.java)
    private val warned = AtomicBoolean(false)

    override fun read(pluginId: String, key: String): String? {
        warnOnce()
        return null
    }

    override fun write(pluginId: String, key: String, value: String) {
        warnOnce()
    }

    private fun warnOnce() {
        if (warned.compareAndSet(false, true)) {
            logger.warn("NoPersistenceStrategy is in use - not recommended for productive software, no plugin state survives a restart")
        }
    }
}
