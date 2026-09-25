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

package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.slf4j.LoggerFactory

/**
 * A [PluginSecurityStrategy] that performs no check at all and always accepts the candidate.
 *
 * Naming this strategy "insecure" is deliberate: a location's fallback chain starts out empty by
 * default (see [PluginSecurity]), so opting into no security check at all requires
 * explicitly adding this strategy, rather than it being an implicit default.
 */
class InsecureSecurityStrategy : PluginSecurityStrategy {
    private val logger = LoggerFactory.getLogger(InsecureSecurityStrategy::class.java)

    override fun check(result: PluginScanResult): PluginSecurityCheckResult {
        logger.debug("No security check performed for candidate '{}', passed through as insecure", result.path)
        return PluginSecurityCheckResult.Success
    }
}
