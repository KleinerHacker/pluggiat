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

package org.pcsoft.framework.pluggiat.scanner

/**
 * Thrown while a plugin candidate's own bytes are read, unpacked or parsed and one of
 * [org.pcsoft.framework.pluggiat.PluginResourceLimits]'s bounds is exceeded - an oversized candidate,
 * an archive that unpacks to far more than it claims, too deeply nested archives, or an oversized
 * manifest.
 *
 * Deliberately a distinct type rather than a generic failure: reaching a limit is a finding about the
 * candidate (it is treated as a [PluginScanStatus.SECURITY_PROBLEM]), not an internal error, and it
 * must never be confused with the candidate merely being unreadable.
 */
class PluginContentLimitExceededException(message: String) : RuntimeException(message)
