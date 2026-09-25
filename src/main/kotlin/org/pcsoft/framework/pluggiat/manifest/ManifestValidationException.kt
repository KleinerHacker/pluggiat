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

package org.pcsoft.framework.pluggiat.manifest

/**
 * Thrown when a plugin manifest fails schema validation or cannot otherwise be parsed into a
 * [PluginManifest].
 *
 * @property violations human-readable descriptions of the individual schema violations found, empty
 * if the manifest could not be parsed at all
 */
internal class ManifestValidationException(
    message: String,
    val violations: List<String> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)
