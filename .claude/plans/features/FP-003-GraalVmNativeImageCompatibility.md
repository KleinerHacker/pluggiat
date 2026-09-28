# Feature Plan: GraalVM Native Image Compatibility

## 1. Objective

pluggiat is to optionally run in a host built as a GraalVM `native-image`, **without** restricting or
changing its existing use on a regular JVM (including GraalVM as a plain JIT distribution) in any
way. This is not a migration but an additional, purely additive support for a second deployment
mode.

## 2. Current State

* pluggiat is a library (a single Gradle module, `build.gradle.kts`) that is embedded into a host -
  not a standalone executable program. A native image is therefore built by the **host**, not by
  pluggiat itself; pluggiat can only provide the necessary prerequisites (reachability metadata,
  supported code paths).
* Three mechanisms are fundamentally incompatible with GraalVM `native-image`'s closed-world
  assumption (no classes unknown at build time, no later dynamic loading):
  1. `PluginClassLoader`/`PinnedPluginClassLoader` (`src/main/kotlin/.../classloader/`) load
     plugin JARs/ZIPs dynamically from disk, whose concrete classes are only known at runtime -
     the exact opposite of a closed world.
  2. The in-VM sandbox (`AgentInstrumentationStrategy`,
     `sandbox/agent/PluginSandboxAgent.kt`) instruments plugin bytecode at runtime via
     `java.lang.instrument.Instrumentation`, activated by the `-javaagent` parameter -
     `native-image` does not support Java agents at runtime.
  3. Jackson (`jackson-dataformat-yaml`, `jackson-module-kotlin`, see
     `manifest/ManifestParser.kt`) uses reflection that `native-image` cannot resolve without
     previously generated reachability metadata - even for host-owned DTOs known at build time,
     such as `PluginManifest`.
  4. The aggregation/proxy mechanism based on Byte Buddy
     (`net.bytebuddy:byte-buddy`, see `ExtensionDecorator.kt`) also creates new classes at runtime
     - usable with `native-image` only via its own, restricted proxy/agent support, not in the
     existing form.
* `ProcessIsolationStrategy` (`sandbox/process/ProcessIsolationStrategy.kt`, FP-002/IP-04) is
  already fully implemented: a plugin runs in its own JVM subprocess and communicates exclusively
  via an ASN.1 DER TLV protocol (Bouncy Castle) on a `java.net` socket
  (`sandbox/process/der/DerCodec.kt`, `PluginProcessManager.kt`). The host hands the
  security-checked plugin bytes (JAR or ZIP of JARs) to the subprocess once at startup through its
  stdin pipe (`Process.getOutputStream()`); the subprocess builds an in-memory
  `PinnedPluginClassLoader` from them, there is no temporary directory. According to the KDoc in
  `ProcessIsolationStrategy`, a process-isolated plugin is nevertheless still loaded **in the
  host** via the regular `PluginLoader` path (for manifest parsing, dependency resolution,
  extension point/configuration mapping) - only the instantiation of the extension implementation
  classes is omitted there. This in-host class loading of the plugin JAR would remain a blocker
  under `native-image`, even in process isolation mode.
* There is neither a GraalVM reachability metadata delivery nor a CI job that checks the
  `native-image` compatibility of the library itself.
* The build is `jvmToolchain(25)`, a single Gradle module, no GraalVM plugin included.

## 3. Target State

* A host can continue to use pluggiat unchanged on any regular JVM (including GraalVM as a JIT
  distribution) - all three sandbox/load modes (in-VM without sandbox, in-VM with agent sandbox,
  process isolation) work exactly as today, without any configuration change.
* A host that is itself built as a GraalVM `native-image` can embed pluggiat **exclusively in
  process isolation mode**, provided the host-owned part of pluggiat that is known at build time
  (configuration, manifest DTOs, `PluginManager` facade, IPC client side) runs under
  `native-image`. The plugin subprocess itself remains a regular (non-native) JVM and is not
  affected by this restriction.
* pluggiat ships its own GraalVM reachability metadata in the published JAR
  (`META-INF/native-image/org.pcsoft.framework/pluggiat/*.json`) for its host-side reflective
  accesses known at build time (manifest DTOs via Jackson), so that a host does not have to
  maintain them by hand during the `native-image` build.
* A host that tries to activate an unsupported mode under `native-image` (pure in-VM class loading
  of a plugin, agent-based in-VM sandbox) receives, by default, an early, unambiguous error
  message instead of an undefined runtime error or silent misbehavior - but can deliberately
  force this per affected strategy via an explicit opt-in field (`forceOnNativeImage`, default
  `false`).
* The restrictions and the supported mode are documented in the MkDocs security/operations chapter
  (with reference to the security notice already present on the main page).

## 4. Requirements

### Functional Requirements

* Existing hosts on a regular JVM are not functionally affected by this feature - no behavior
  change, no new mandatory configuration step.
* pluggiat itself detects whether the host runs under `native-image` and rejects an unsupported
  sandbox/load configuration (in-VM class loading of a plugin, `AgentInstrumentationStrategy`)
  with a meaningful exception instead of failing or showing wrong behavior - unless the affected
  strategy was explicitly exempted from this protection via `forceOnNativeImage = true`.
* A host that uses `native-image` and configures process isolation exclusively can use pluggiat
  with the same `PluginManager`/`PluginSandbox` API as on the regular JVM.
* pluggiat's own host-side reflective accesses known at build time (Jackson manifest DTOs) can be
  resolved for a host `native-image` build without manual rework via shipped reachability
  metadata.

### Technical Requirements

* No change to the existing in-VM load/sandbox paths for regular JVM use; the feature is purely
  additive (new metadata files, a new runtime check, a new, optional CI verification step).
* Reachability metadata is generated exclusively for pluggiat's own classes known at build time
  (manifest DTOs, internal configuration types) - never for plugin classes, which are by
  definition only known at runtime and cannot in principle be captured under `native-image`.
* The detection of whether the host runs under `native-image` uses the GraalVM SDK library
  `org.graalvm.sdk:nativeimage` (`ImageInfo.inImageCode()`) as a new third-party dependency
  confirmed by the user (see `dependencies.md`, section 9).
* No change to `ProcessIsolationStrategy`'s existing IPC protocol (Bouncy Castle ASN.1 DER on
  `java.net` sockets) and to the stdin hand-over of the plugin bytes at subprocess startup; the
  plugin subprocess remains a regular JVM.
* A CI verification step (see the `ci-pipeline` skill before changing workflow files) builds a
  minimal native test host image against pluggiat's process isolation mode as a trial, to detect
  reachability metadata regressions - without changing the existing `build` task or its runtime on
  the regular JVM.
* **Each implementation plan delivers, on its own, a working intermediate state verified by the
  regular test suite (on a regular JVM, without a real `native-image` build).** IP-01 and IP-02
  must therefore be cut so that their respective results can be secured with ordinary JUnit
  tests; the heavyweight, real `native-image` build (IP-03) is an additional end-to-end
  safeguard, not the only place where the new behavior is verified.

## 5. Architecture

* No new Gradle (sub-)module for pluggiat itself - the library remains a single module. For the CI
  verification step (IP-03), only a separate, minimal test host setup is needed (e.g. under
  `src/nativeImageTest/` or as a standalone example project) that serves verification purposes
  only and is not part of the published artifact.
* New directory `src/main/resources/META-INF/native-image/org.pcsoft.framework/pluggiat/` with
  `reflect-config.json` (and possibly `resource-config.json`) for the Jackson-based manifest DTOs
  (`manifest/ManifestParser.kt` and the associated data classes) - automatically becomes part of
  the JAR and is picked up by GraalVM during the `native-image` build of a host that includes
  pluggiat as a dependency, via the standard mechanism (`native-image` reads metadata shipped in
  JARs).
* A new, small facade function/class (e.g. `org.pcsoft.framework.pluggiat.NativeImageSupport`)
  that centrally checks whether the host currently runs under `native-image` (via
  `org.graalvm.nativeimage.ImageInfo.inImageCode()`, see section 9).
  `PluginManager`/`PluginSandbox` query this facade at exactly the places where comparable
  precondition checks already take place today (analogous to `SandboxAgentNotActiveException` in
  `AgentInstrumentationStrategy`/`ProcessIsolationStrategy`), and throw a new, dedicated exception
  (e.g. `NativeImageUnsupportedModeException`) if the active configuration requires in-VM class
  loading or agent instrumentation under `native-image`. `NativeImageSupport` encapsulates the
  `ImageInfo.inImageCode()` call behind a replaceable seam (e.g. an internal function/property
  that can be overridden at test time), so that regular JUnit tests on a regular JVM can simulate
  "runs under `native-image`" without needing a real native build.
* Every strategy that is fundamentally not sensibly usable under `native-image`
  (`AgentInstrumentationStrategy` for the in-VM sandbox, the in-VM class loading path in
  `PluginLoader`/`PluginClassLoader`) receives a new, optional constructor/configuration field
  (working name `forceOnNativeImage: Boolean = false`), analogous to the existing explicit opt-in
  nature of `InsecureSecurityStrategy`. The new runtime guard (see above) throws the
  `NativeImageUnsupportedModeException` only if `NativeImageSupport` reports "runs under
  `native-image`" **and** this field is `false` (default). If it is set to `true`, the strategy is
  executed unchanged - the host thereby deliberately assumes responsibility for behavior that
  pluggiat has not tested/does not support (e.g. because it has its own, compatible native setup or
  knowingly accepts the risk). Behavior on a regular JVM is completely unaffected by this field
  (the guard never applies there).
* No change to `PluginSandbox`, `PluginSandboxStrategy`, `ProcessIsolationStrategy` themselves
  apart from the additional early check; their existing API surface remains unchanged.
* MkDocs: a new section (`docs/docs/host-integration/` or as a separate page
  `graalvm.md`/`.de.md`) on using GraalVM `native-image` - supported mode (process isolation
  only), restrictions, reference to the existing security notice on the main page.

## 6. Implementation Plan Overview

| ID    | Implementation Plan                              | Objective                                                                 | Dependencies |
| ----- | ------------------------------------------------- | -------------------------------------------------------------------------- | ------------ |
| IP-01 | Reachability metadata for manifest parsing        | Ship GraalVM metadata for Jackson-based, host-known DTOs                  | -            |
| IP-02 | Runtime guard against unsupported modes           | Early, unambiguous error message for in-VM modes under `native-image`     | -            |
| IP-03 | CI verification & documentation                   | Build a native test host image against process isolation; MkDocs chapter  | IP-01, IP-02 |

## 7. Implementation Plans

### IP-01: Reachability metadata for manifest parsing

**Objective**

pluggiat's own host-side reflective accesses known at build time (Jackson deserialization of the
manifest DTOs) can be resolved for a host `native-image` build without manual rework.

**Scope**

* Generation/maintenance of `reflect-config.json` (possibly `resource-config.json`) under
  `src/main/resources/META-INF/native-image/org.pcsoft.framework/pluggiat/` for all
  Jackson-deserialized types in `manifest/` (including `SpdxLicenses.kt`, if integrated
  reflectively).
* A new test running on a regular JVM that checks the metadata file(s) for structural consistency
  with the actual code (e.g.: every class listed in `reflect-config.json` exists via
  `Class.forName`, every listed field/constructor exists via reflection) - detects drift between
  metadata and code already in the regular test run, without needing a real `native-image` build.
* Explicitly **not** part of it: any metadata for plugin-owned classes - these are fundamentally
  unknown at the host's build time and cannot be captured.

**Affected Areas**

* `src/main/kotlin/org/pcsoft/framework/pluggiat/manifest/` (read-only for analysis)
* New directory `src/main/resources/META-INF/native-image/...`

**Dependencies**

* -

**Expected Result**

A host that includes pluggiat and builds `native-image` itself no longer has to add its own
reachability metadata by hand for manifest deserialization. The consistency test already runs
green with the existing `test` task on a regular JVM and protects the metadata against drift
independently of IP-03.

**Technical Considerations**

* Metadata generation should be traceable/repeatable (e.g. via the GraalVM tracing agent against a
  real test run, not merely guessed manually) - details on the exact generation method are to be
  clarified when implementing this plan.
* Without effect on a regular JVM: `META-INF/native-image/...` files are ignored by a regular JVM,
  hence no risk at all for existing use.

### IP-02: Runtime guard against unsupported modes

**Objective**

A host that activates a configuration incompatible with `native-image` under `native-image` (in-VM
plugin class loading, agent-based in-VM sandbox) receives, by default, an early, unambiguous error
message instead of an undefined state - but can deliberately force this via an explicit opt-in
field per strategy.

**Scope**

* New central detection facade for "runs under `native-image`".
* New exception class, thrown at the existing precondition check points (analogous to
  `SandboxAgentNotActiveException`).
* New optional field `forceOnNativeImage: Boolean = false` on every affected strategy
  (`AgentInstrumentationStrategy`, in-VM class loading path); when `true`, it suppresses the new
  exception for exactly this strategy but otherwise changes nothing about its behavior.
* New tests running on a regular JVM that simulate "runs under `native-image`" via the test seam of
  `NativeImageSupport` and cover at least the following cases per affected strategy: the guard
  throws `NativeImageUnsupportedModeException` with `forceOnNativeImage = false` (default); the
  guard throws nothing with `forceOnNativeImage = true`; the guard throws nothing if
  `NativeImageSupport` reports "regular JVM", regardless of the field value.
* Explicitly **not** part of it: any functional change to in-VM behavior on a regular JVM - the
  field has no effect there whatsoever.

**Affected Areas**

* New file for the detection facade (package suggestion: `org.pcsoft.framework.pluggiat`, root
  level, analogous to existing root classes such as `PluginManager`)
* `sandbox/PluginSandbox.kt`, `sandbox/AgentInstrumentationStrategy.kt` (or its actual file name),
  `classloader/PluginLoader.kt` - in each case only the place where comparable preconditions are
  already checked today

**Dependencies**

* -

**Expected Result**

A `native-image` host with a wrong configuration receives, by default, a clear, documented
exception instead of a silent or late-occurring error - but can deliberately override this per
strategy via `forceOnNativeImage = true`. The entire guard behavior (default throw, opt-in bypass,
ineffectiveness on a regular JVM) is already verified green with the existing `test` task on a
regular JVM, regardless of whether IP-03 has been implemented yet.

**Technical Considerations**

* The detection "runs under `native-image`" uses `org.graalvm.sdk:nativeimage`
  (`ImageInfo.inImageCode()`), see section 9.
* The check must not cause any measurable additional cost on a regular JVM (simple boolean check,
  no overhead in the hot path).

### IP-03: CI verification & documentation

**Objective**

The `native-image` compatibility of pluggiat's own code (not of the plugins) is verified
end-to-end by a real `native-image` build, in addition to the JVM unit tests already present from
IP-01/IP-02, and documented for host developers.

**Scope**

* A minimal test host setup that includes pluggiat exclusively in process isolation mode and is
  built as a trial with GraalVM `native-image` - as a separate, optional CI job (see the
  `ci-pipeline` skill before changing workflow files) that does not slow down or change the
  existing `build` job.
* MkDocs chapter on GraalVM use (supported mode, restrictions, reference to the existing security
  notice on the main page) - load the `project-docs` skill after implementation.
* Explicitly **not** part of it: support for in-VM modes under `native-image` - according to the
  target state, these remain fundamentally unsupported.

**Affected Areas**

* `.github/` (new, optional workflow or job)
* `docs/docs/` (new page or new section)

**Dependencies**

* IP-01, IP-02 (the behavior to be verified/documented must already exist)

**Expected Result**

A regression of `native-image` compatibility is detected in CI before it reaches a host; host
developers find clear instructions on how to use pluggiat under `native-image` and what is not
supported.

**Technical Considerations**

* The native-image build itself is compute-intensive - to be designed as a separate job that may
  not run on every push (details at implementation, the `ci-pipeline` skill is authoritative).

## 8. Dependency Graph

```text
IP-01
IP-02
IP-03 (depends on IP-01 and IP-02)
```

## 9. Risks and Open Questions

* **Resolved - new third-party dependency for `native-image` detection**: The GraalVM SDK library
  `org.graalvm.sdk:nativeimage` (`ImageInfo.inImageCode()`) was confirmed by the user as a new
  third-party dependency (see `dependencies.md`) and is used for `native-image` detection in
  IP-02; no heuristic without an SDK API is needed.
* **Fundamental limit remains**: Pure in-VM plugin class loading can **fundamentally never** be
  supported under `native-image`, regardless of implementation effort - this is a structural
  property of `native-image`'s closed-world model, not a solvable implementation detail. This
  feature therefore does not create full parity of all modes, but exclusively support for the
  already existing process isolation mode. `forceOnNativeImage` does not lift this structural
  limit but deliberately shifts the responsibility for it to the host.
* **License report/CycloneDX BOM**: A new third-party dependency (if confirmed) must pass the
  existing license check Gradle task (see `dependencies.md` - licensing section).
* **Scope of the reachability metadata**: If host code (not only pluggiat's own DTOs) needs
  additional, host-specific reflection, that remains outside the scope of this feature - a host
  must maintain its own, additional metadata itself.
* **CI costs**: An additional native build job in the pipeline increases CI runtime/costs;
  frequency (every push vs. only on release/tag) is to be clarified when implementing IP-03.
* **Open - subprocess startup under `native-image`**: `PluginProcessManager` starts the subprocess
  with the host's class path (`java.class.path`) and the path of the Java agent JAR; whether and
  how the host has to provide a class path for this under `native-image` is to be clarified when
  implementing IP-03. The stdin hand-over of the plugin bytes at startup only uses
  `java.lang.Process` and is independent of this.

## 10. Feature Completion Criteria

* A host on a regular JVM uses pluggiat exactly as before this feature - no behavior change, no
  new mandatory step.
* A host built as a GraalVM `native-image` that configures process isolation exclusively can
  demonstrably (verified by the CI job from IP-03) include pluggiat and perform extension calls
  via the subprocess.
* A `native-image` host that activates an unsupported configuration (in-VM class loading, agent
  sandbox) without `forceOnNativeImage` set demonstrably receives an early, unambiguous exception
  instead of undefined misbehavior.
* A `native-image` host that sets `forceOnNativeImage = true` for such a strategy can demonstrably
  execute it unchanged (without the new exception).
* pluggiat's own manifest DTOs require no reachability metadata maintained by the host itself for a
  host `native-image` build.
* The restrictions (no in-VM mode under `native-image`, the reason for it, meaning of
  `forceOnNativeImage`) are documented in the MkDocs chapter.
* After completion of IP-01 and IP-02 - before IP-03 - the entire new functionality (metadata
  consistency, guard default, `forceOnNativeImage` bypass) is verified green by regular JUnit
  tests on a regular JVM, without needing a real `native-image` build.
