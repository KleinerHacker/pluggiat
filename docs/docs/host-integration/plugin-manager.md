# PluginManager

`PluginManager` is the central, host-wide entry point of the framework. It is built through a
Kotlin builder DSL:

```kotlin
val manager = pluginManager {
    location(PluginLocation(Paths.get("/opt/myapp/plugins"), PluginLocationType.EXTERNAL))
    defaultSecurityChain(PluginLocationType.EXTERNAL, listOf(InsecureSecurityStrategy()))
    dependencyStrategy = UnrestrictedPluginDependencyStrategy()
    persistenceStrategy = FilePersistenceStrategy(Paths.get("/var/lib/myapp/plugin-state.properties"))
    exceptionHandlingStrategy = DefaultExceptionHandlingStrategy()
    sdkWhitelistEntry(SdkWhitelistEntry("com.myapp.sdk"))
    hostVersion = "2.3.0"
}
```

## What it configures

`PluginManagerConfiguration` (the builder's receiver) bundles every host-wide setting the framework needs in one place:

* `pluginLocations` - the locations to scan (via `location(...)`).
* `defaultSecurityChains` - per-`PluginLocationType` default security chain (via
  `defaultSecurityChain(...)`), see [Security](security.md).
* `dependencyStrategy` - the global `PluginDependencyStrategy`.
* `persistenceStrategy` - the single `PluginPersistenceStrategy` instance, see
  [Persistence](persistence.md).
* `exceptionHandlingStrategy` - the single, host-wide `ExceptionHandlingStrategy`, see
  [Plugin lifecycle management](plugin-lifecycle-management.md).
* `sdkWhitelist` - packages of your own SDK exposed to plugins (via `sdkWhitelistEntry(...)`).
* `hostVersion` - your application's own version.

## What it provides

```kotlin
manager.scanner  // PluginScanner, pre-wired with defaultSecurityChains
manager.security // PluginSecurity, also used to pre-wire scanner
manager.loader   // PluginLoader, pre-wired with sdkWhitelist
```

These pre-wired instances are a convenience only - the underlying `PluginScanner`/`PluginLoader`
constructor parameters remain directly usable if you prefer to wire things yourself.

## Reactivation

```kotlin
val result = manager.reactivate(location, path, manifest, dependencies)
```

Re-checks the security chain against the candidate at `path` first, via `manager.security`, and
only reloads it via `manager.loader` on success - persisting it as enabled again through
`PluginManagerConfiguration.persistenceStrategy`. On a failed re-check, the plugin stays disabled (its disable reason is
updated) and no automatic force-load happens; a host wanting to force-load anyway calls `loader`
directly, exactly as for any other force-load. See
[Plugin lifecycle management](plugin-lifecycle-management.md) for the full picture.

## Current scope

For now, `PluginManager` is a configuration/access facade plus the one small reactivation
orchestration step above - the full scan-load-enable orchestration flow (plugin id collision
handling, `minVersion` enforcement against `hostVersion`, ...) is planned as a later addition.
