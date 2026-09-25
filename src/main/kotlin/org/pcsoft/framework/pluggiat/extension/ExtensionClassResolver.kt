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
 * Resolves the fully qualified class name of a plugin extension implementation, as declared under
 * `extensions.<key>[].implementation`, to a loadable [Class].
 */
interface ExtensionClassResolver {
    /**
     * Resolves [fqcn] to its [Class].
     *
     * @throws ClassNotFoundException if no class with this name can be loaded
     */
    fun resolve(fqcn: String): Class<*>
}

/**
 * Default [ExtensionClassResolver], resolving classes via the calling class loader.
 *
 * Not isolated to a specific plugin's class loader; a dedicated, classloader-isolated
 * implementation is provided as part of the plugin class loading mechanism.
 */
internal object DefaultExtensionClassResolver : ExtensionClassResolver {
    override fun resolve(fqcn: String): Class<*> = Class.forName(fqcn)
}
