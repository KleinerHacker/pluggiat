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

/**
 * A single package of the host application's SDK, exposed to plugins through their isolated
 * class loader.
 *
 * @property packageName the package to expose, e.g. `"org.pcsoft.framework.myapp.sdk"`
 * @property recursive whether sub-packages of [packageName] are exposed as well (`true`, default),
 * or only classes directly inside [packageName] (`false`)
 */
data class SdkWhitelistEntry(
    val packageName: String,
    val recursive: Boolean = true,
)
