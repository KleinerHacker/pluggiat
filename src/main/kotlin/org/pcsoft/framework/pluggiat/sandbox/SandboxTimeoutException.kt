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

import java.time.Duration

/**
 * Thrown by [PluginSandbox.runGoverned] when a governed call for [pluginId] does not complete
 * within [timeout] ([PluginSandboxPolicy.callTimeout]) - see [ThreadWatchdog] for how the
 * underlying, now-abandoned worker thread is handled.
 */
class SandboxTimeoutException(pluginId: String, timeout: Duration) :
    RuntimeException("Plugin '$pluginId' exceeded its sandbox call timeout of $timeout")
