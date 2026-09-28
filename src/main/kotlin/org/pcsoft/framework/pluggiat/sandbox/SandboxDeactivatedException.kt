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

/**
 * Thrown by [PluginSandbox.runGoverned] when [pluginId] was deactivated (see
 * [PluginSandbox.deactivate]) and not re-activated since - a governed call racing against a
 * concurrent unload of the same plugin fails fast instead of silently creating a fresh executor for
 * code that should no longer be running.
 */
class SandboxDeactivatedException(pluginId: String) :
    RuntimeException("Plugin '$pluginId' is deactivated and cannot run a governed call right now")
