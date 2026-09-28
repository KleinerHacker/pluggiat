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

package org.pcsoft.framework.pluggiat.sandbox.process

import java.lang.reflect.Method

/**
 * Thrown by a process-isolation extension proxy (see [ProcessIsolationStrategy]) at call time when
 * [method]'s signature cannot be mapped onto the minimal ASN.1 DER type set IP-04 supports (see
 * `org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue`) - an unsupported parameter
 * or return type, a `Map`, a nested generic, etc.
 *
 * This is a **deliberate, permanent limitation** of process-isolated plugins, not a TODO: such a
 * method can never be called across the process boundary, regardless of the subprocess's state. It
 * is thrown immediately by [SandboxTypeSupport] at the proxy call site, *before* any IPC traffic -
 * the subprocess is never contacted for an unsupported call, so it can never leave the subprocess
 * side in an inconsistent state.
 *
 * A host that needs a process-isolated plugin to expose such a signature has exactly two options:
 * narrow the extension point API to the supported type set, or run that plugin with
 * [org.pcsoft.framework.pluggiat.sandbox.SandboxIsolationLevel.IN_VM] instead (optionally still
 * governed by IP-02/IP-03's in-VM mechanisms).
 */
class UnsupportedSandboxTypeException(method: Method, reason: String) :
    RuntimeException(
        "Method '${method.declaringClass.name}#${method.name}' is not callable on a process-isolated plugin: $reason. " +
            "This is a permanent limitation of process isolation (IP-04), not a temporary error - see the KDoc of " +
            "org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue for the supported type set.",
    )
