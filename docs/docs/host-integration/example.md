# Complete example: embedding pluggiat into a report application

This page walks through embedding pluggiat into a fictional host application, "ReportApp", tying
together plugin locations, the extension point it offers, its security setup, persistence, the SDK
whitelist and lifecycle management into one coherent setup. See
[Plugin development: complete example](../plugin-development/example.md) for a plugin written
against exactly this host.

## The scenario

ReportApp ships with one built-in plugin and lets users install additional, third-party ones:

* `plugins/builtin/` - shipped with the application itself, always trusted.
* `plugins/external/` - a user-writable folder for third-party plugins, packaged as signed `.zip`
  files; unsigned or tampered ones must be rejected by default, with an explicit user override
  path.
* Plugins contribute a "Report" export format through an `exporters` extension point.
* Enabled/disabled state and any user-approved security overrides must survive an application
  restart.

## Defining the extension point

ReportApp's own plugin API - the only pluggiat-unrelated type a plugin author ever needs to know -
is a plain interface:

```kotlin
package com.example.reportapp.api

interface Exporter {
    fun export(data: List<Row>): ByteArray
}
```

Paired with a host-defined configuration class, per
[Defining extension points](extension-points.md):

```kotlin
package com.example.reportapp.api

import org.pcsoft.framework.pluggiat.extension.ExtensionConfiguration
import org.pcsoft.framework.pluggiat.extension.ExtensionPoint
import kotlin.reflect.KClass

@ExtensionPoint(key = "exporters", exclusive = false)
data class ExporterConfig(
    override val implementation: KClass<out Exporter>,
    val fileExtension: String,
    val displayName: String,
) : ExtensionConfiguration<Exporter>
```

## SDK whitelist

Only `com.example.reportapp.api` (this `Exporter` interface, plus the `Row` DTO plugins exchange
data through) is ever meant to be visible to plugin code - nothing else in ReportApp's own packages
should be reachable, per [SDK whitelist](sdk-whitelist.md):

```kotlin
sdkWhitelistEntry {
    packageName = "com.example.reportapp.api"
}
```

## Security: different trust per location

The built-in location needs no protection - it is ReportApp's own code. The external location must
default to rejecting anything not signed with ReportApp's publisher key, while still letting a user
explicitly approve a specific plugin afterward. Per [Security](security.md#which-strategy-should-i-use):

```kotlin
val reportAppPublicKey: PublicKey = loadReportAppSigningKey()

defaultSecurityChain {
    type = PluginLocationType.BUILTIN
    addStrategy(InsecureSecurityStrategy())
}
defaultSecurityChain {
    type = PluginLocationType.EXTERNAL
    addStrategy(SignatureSecurityStrategy(DirectPublicKeyProviderStrategy(reportAppPublicKey)))
}
```

A candidate that fails this chain is reported as `SECURITY_PROBLEM`, not silently rejected - see
[the approval flow](#the-approval-flow-for-a-rejected-plugin) below for what ReportApp does with it.

## Persistence: surviving a restart

Enabled/disabled status and (once a user approves an unsigned plugin) the resulting persisted
security exception both need to survive a restart. A single file is enough for ReportApp's needs,
per [Persistence](persistence.md#filepersistencestrategy):

```kotlin
val persistence = FilePersistenceStrategy(
    path = Paths.get(System.getProperty("user.home"), ".reportapp", "plugin-state.properties"),
)
```

## Assembling the `PluginManager`

Everything above comes together in one `pluginManager { ... }` block, per
[PluginManager](plugin-manager.md):

```kotlin
val manager = pluginManager {
    location {
        path = Paths.get(installDir, "plugins", "builtin")
        type = PluginLocationType.BUILTIN
        scanStrategy = SingleJarScanStrategy()
    }
    location {
        path = Paths.get(userDataDir, "plugins", "external")
        type = PluginLocationType.EXTERNAL
        scanStrategy = ZipJarScanStrategy() // default, shown here for clarity
    }
    defaultSecurityChain {
        type = PluginLocationType.BUILTIN
        addStrategy(InsecureSecurityStrategy())
    }
    defaultSecurityChain {
        type = PluginLocationType.EXTERNAL
        addStrategy(SignatureSecurityStrategy(DirectPublicKeyProviderStrategy(reportAppPublicKey)))
    }
    dependencyStrategy = UnrestrictedPluginDependencyStrategy()
    persistenceStrategy = persistence
    exceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    sdkWhitelistEntry {
        packageName = "com.example.reportapp.api"
    }
    extensionPoint(ExporterConfig::class)
    hostVersion = ReportApp.VERSION // e.g. "1.4.2" - checked against each plugin's minVersion
}
```

## Scanning at startup

```kotlin
manager.scan()

val problems = manager.scanResults.filterNot { it.status == PluginScanStatus.LOADED }
for (problem in problems) {
    logger.warn("Plugin at {} not loaded: {} ({})", problem.path, problem.status, problem.errorMessage)
}

val exporters: List<Exporter> = manager.getExtensions("exporters")
exportMenu.populate(exporters.map { it to (manager.registry.registrationFor("exporters")) })
```

This single call resolves id collisions across `builtin`/`external`, checks every candidate's
`minVersion` against `ReportApp.VERSION`, loads everything that passes in dependency order, and
activates their extensions - see [Id collisions and minVersion](plugin-manager.md#id-collisions-and-minversion).

## The approval flow for a rejected plugin

Continuing the [report exporter example](../plugin-development/example.md): a user downloads an
unsigned, third-party build of `com.example.report-exporter-plugin` and drops it into
`plugins/external/`. On the next `scan()`, it comes back as `SECURITY_PROBLEM` - the signature
check failed and there is no other strategy in the chain to fall back on. Per
[the host approval flow](security.md#host-approval-flow-after-a-security_problem):

```kotlin
val rejected = manager.scanResults.first { it.status == PluginScanStatus.SECURITY_PROBLEM }

if (userConfirmsDialog("'${rejected.manifest?.name}' is not signed by a known publisher. Load it anyway?")) {
    manager.forceLoad(rejected.manifest!!.id)
    // No PersistableSecurityStrategy here (SignatureSecurityStrategy has no persistable state),
    // so make the override stick with a generic exception instead:
    manager.forceLoad(rejected.manifest!!.id, persistException = true)
}
```

From this point on, every future `scan()`/`reload()` for this specific plugin id skips its security
chain entirely - logged as a `WARN` each time, so this stays visible in ReportApp's own logs, and
reviewable later via [Troubleshooting](troubleshooting.md#pluginscanstatus-reference).

## Letting the user disable a plugin

```kotlin
manager.unload("com.example.report-exporter-plugin")
```

This runs the plugin's `onDisable`/`onUnload` hooks, persists `"enabled" = "false"` and
`"disabledReason" = "USER"` via `persistence`, and closes its class loader - see
[Plugin lifecycle management](plugin-lifecycle-management.md#disable-reason). Re-enabling it later
is a plain `manager.reload("com.example.report-exporter-plugin")`, which re-checks security first
(and, for this specific plugin id, immediately succeeds again because of the persisted exception
from the approval flow above).
