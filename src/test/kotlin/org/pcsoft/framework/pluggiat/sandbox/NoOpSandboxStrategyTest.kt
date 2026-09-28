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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.manifest.PluginManifest

/**
 * Verifies [NoOpSandboxStrategy] performs no enforcement at all: [NoOpSandboxStrategy.activate]
 * always reports [SandboxCheckResult.Success], regardless of the given [PluginSandboxPolicy].
 */
class NoOpSandboxStrategyTest {

    private fun loadedPlugin(): LoadedPlugin = LoadedPlugin(
        pluginId = "example",
        manifest = PluginManifest(id = "example", name = "example", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg=="),
        classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList()),
    )

    /**
     * Use case: activation always succeeds under the fully permissive default policy.
     */
    @Test
    fun `activate succeeds under the unrestricted policy`() {
        val result = NoOpSandboxStrategy().activate(loadedPlugin(), PluginSandboxPolicy.UNRESTRICTED)

        assertEquals(SandboxCheckResult.Success, result)
    }

    /**
     * Use case: activation succeeds even under a restrictive policy - a `NoOpSandboxStrategy` performs
     * no real enforcement, so it never rejects any policy.
     */
    @Test
    fun `activate succeeds even under a restrictive policy`() {
        val restrictivePolicy = PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.NETWORK))

        val result = NoOpSandboxStrategy().activate(loadedPlugin(), restrictivePolicy)

        assertEquals(SandboxCheckResult.Success, result)
    }
}
