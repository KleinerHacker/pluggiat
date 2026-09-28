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

package org.pcsoft.framework.pluggiat.sandbox

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [PluginSandboxPolicy.requiresApiMediation]'s rule: it is `false` exactly for the fully
 * permissive default and `true` as soon as at least one [SandboxApiCategory] is restricted.
 */
class PluginSandboxPolicyTest {

    /**
     * Use case: the shared [PluginSandboxPolicy.UNRESTRICTED] default does not require API mediation -
     * an inactive `pluggiat` Java agent is harmless for it.
     */
    @Test
    fun `unrestricted default does not require api mediation`() {
        assertFalse(PluginSandboxPolicy.UNRESTRICTED.requiresApiMediation)
    }

    /**
     * Use case: a freshly constructed policy with the default (all-categories) `allowedApiCategories`
     * also does not require API mediation, matching the shared [PluginSandboxPolicy.UNRESTRICTED].
     */
    @Test
    fun `a policy allowing all categories does not require api mediation`() {
        assertFalse(PluginSandboxPolicy().requiresApiMediation)
    }

    /**
     * Use case: a policy that restricts at least one category requires the sandbox agent to be active
     * in order to be enforceable.
     */
    @Test
    fun `a policy restricting at least one category requires api mediation`() {
        val policy = PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.NETWORK))

        assertTrue(policy.requiresApiMediation)
    }

    /**
     * Use case: a policy restricting every category also requires API mediation.
     */
    @Test
    fun `a policy restricting every category requires api mediation`() {
        val policy = PluginSandboxPolicy(allowedApiCategories = emptySet())

        assertTrue(policy.requiresApiMediation)
    }
}
