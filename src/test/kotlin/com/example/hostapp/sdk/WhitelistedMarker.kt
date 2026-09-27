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

package com.example.hostapp.sdk

/**
 * Stand-in for a class directly inside a host SDK package exposed to plugins via a
 * [org.pcsoft.framework.pluggiat.classloader.SdkWhitelistEntry] in
 * `org.pcsoft.framework.pluggiat.classloader.PluginClassLoaderTest`.
 *
 * Deliberately outside the `org.pcsoft.framework.pluggiat` namespace: classes below that prefix are
 * always delegated to the host class loader by
 * [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader], so a fixture standing in for a
 * *host application's* class must live in a host-like package to be subject to the SDK whitelist at
 * all.
 */
class WhitelistedMarker
