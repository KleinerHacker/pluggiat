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

package org.pcsoft.framework.pluggiat.classloader

import org.pcsoft.framework.pluggiat.extension.ExtensionClassResolver

/**
 * [ExtensionClassResolver] isolated to a single plugin's [PluginClassLoader], replacing
 * `org.pcsoft.framework.pluggiat.extension.DefaultExtensionClassResolver` for actual plugin
 * loading.
 */
class PluginExtensionClassResolver(private val classLoader: PluginClassLoader) : ExtensionClassResolver {
    // SECURITY: resolution goes through the plugin's own PluginClassLoader, never through the host's
    // SECURITY: loader: the manifest's `implementation` is plugin-controlled text, and resolving it against
    // SECURITY: the host would let a manifest name an arbitrary host class to be instantiated. Going through
    // SECURITY: the plugin loader also keeps the resulting class subject to that plugin's sandbox policy.
    override fun resolve(fqcn: String): Class<*> = classLoader.loadClass(fqcn)
}
