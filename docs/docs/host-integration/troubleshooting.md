# Troubleshooting

pluggiat logs through SLF4J (`org.slf4j`); plug in any binding your application already uses. This
page lists the log level convention across the framework, and the error/conflict cases you are most
likely to see reported.

## Log level overview

* **`INFO`** - scan start per location (location path, scan strategy and location type - the security
  chain is only visible at `TRACE`), a plugin successfully loaded, a permanent enabled/disabled change
  persisted via `PluginPersistenceStrategy` (`reload`, `unload`).
* **`WARN`** - an invalid manifest, a failed security strategy/chain (`SECURITY_PROBLEM`), a candidate
  exceeding a resource limit, an id collision between locations, an exclusive extension point conflict,
  a force-load of a plugin that failed its security check, a persisted checksum via
  `write<ChecksumSecurityStrategy>`, a persisted generic security exception
  (`forceLoad(..., persistException = true)`) and every subsequent check skipped because of it, use of
  `NoPersistenceStrategy`, a non-proxyable custom return type, an `ExceptionHandlingAction.CRASH` about
  to be carried out, a sandbox violation (`SECURITY WARNING - potential attack: ...` for a blocked API
  call, `Sandbox violation for plugin ...` for a `callTimeout` timeout or an unexpectedly exited
  process-isolation subprocess).
* **`DEBUG`** - a plugin's contained extensions (key, implementation class), a decorator instantiating
  an `implementation` class, lifecycle transitions (`onLoad`/`onEnable`/`onDisable`/`onUnload`).
* **`ERROR`** - a plugin forcibly disabled by `ExceptionHandlingAction.UNLOAD`, a plugin forcibly
  unloaded and marked `POTENTIAL_ATTACK` due to a sandbox violation, a plugin whose `onLoad`/`onEnable`
  (while its extensions are resolved) or `onDisable`/`onUnload` (during `unload()`) exceeded its
  sandbox timeout, a cyclic plugin dependency detected during `PluginManager.scan()` (no candidate of
  that scan is loaded).
* **`TRACE`** - fine-grained detail within a single security strategy check (signature entry
  verification, checksum computation, chain evaluation order), and every other security- and
  sandbox-relevant decision (public key resolution, sandbox guard checks, dependency visibility,
  exception-handling resolution).

## `PluginScanStatus` reference

Every plugin candidate ends up with exactly one of these statuses in
`PluginManager.scanResults`/`PluginScanner.scan`'s result:

| Status                  | Meaning                                                                                                   | `manifest` |
|--------------------------|-------------------------------------------------------------------------------------------------------|------------|
| `LOADED`                | Manifest valid, security passed, id collision/`minVersion` passed, class loader created successfully. | present    |
| `MANIFEST_NOT_FOUND`     | No manifest file found for this candidate.                                                              | `null`     |
| `MANIFEST_INVALID`      | A manifest was found but failed schema validation or could not be mapped.                               | `null`     |
| `SECURITY_PROBLEM`      | Every strategy of the location's security chain failed. See [Security](security.md).                    | present    |
| `ID_COLLISION`          | Lost against (or tied with) another candidate of the same plugin id from a different location.           | present    |
| `MIN_VERSION_VIOLATION` | The manifest's `minVersion` is newer than the configured `hostVersion`.                                  | present    |
| `LOAD_FAILED`           | Passed every prior check, but `PluginLoader.load` still failed (e.g. a missing required dependency).      | present    |
| `POTENTIAL_ATTACK`      | A loaded plugin violated its sandbox policy at runtime (or reached the call timeout limit) and was forcibly unloaded. Can never be force-loaded. See [Runtime sandbox](sandbox.md#sandbox-violations-and-potential_attack). | present    |

Only `MANIFEST_NOT_FOUND`/`MANIFEST_INVALID` clear `manifest` - every other non-`LOADED` status keeps
it, so a host can inspect the candidate's declared id/version and, if it decides to, still
`PluginManager.forceLoad` it. The one exception is `POTENTIAL_ATTACK`: there is deliberately no host
override for it. `forceLoad`, `reload` and `reactivate` all throw an `IllegalStateException` for such a
plugin - `reload` and `reactivate` before they run any security re-check, so nothing is loaded or
persisted as enabled.

## Id collision

Two locations contributing a candidate with the same plugin id are resolved purely by version
(Maven version scheme, see `IdCollisionResolver`):

* The strictly higher version wins; the rest of the group is marked `ID_COLLISION`, logged as a
  `WARN`.
* If the highest version is tied between two or more candidates, the **entire group** is rejected
  immediately (`ID_COLLISION`), logged as a `WARN` security warning - there is no further
  tie-breaking (e.g. by checksum).

## Exclusive extension point conflict

If an extension point is declared `exclusive = true` and two independently installed plugins both
contribute to its key, **the extensions of all contributing plugins are dropped** (not just the
individual registration): none of them shows up in `extensionsByKey`, so `getExtensions`/
`getFirstExtension` return nothing from them, and `ExtensionAggregator` reports each of these plugins as
`REJECTED_EXCLUSIVE_CONFLICT`. The conflict is logged as a `WARN` naming all plugin ids and the key.
`PluginManager` itself does not act on that status: the plugins stay in `loadedPlugins` and their
`scanResults` entries keep the status `LOADED`, so the `WARN` log is the place to look. Their
`onLoad`/`onEnable` hooks have already run by then, because every extension is instantiated before the
conflict is detected. This is a conflict of the concrete installed plugin combination, not a bug in the
host's extension point declaration.

## `minVersion` rejection

A candidate's `manifest.minVersion` is compared against `PluginManagerConfiguration.hostVersion`
(Maven version scheme). If `hostVersion` is newer than or equal to `minVersion`, the check passes; if
`hostVersion` is older, the candidate is marked `MIN_VERSION_VIOLATION` and never loaded.
`hostVersion == null` (the default) skips this check entirely.

## Cyclic plugin dependency

If the plugins selected for loading in one `scan()` call form a dependency cycle (`required` or
`optional` alike), `DependencyGraph.topologicalOrder` detects it and **no candidate of that scan run
is loaded at all** - logged as an `ERROR` naming the cycle. Every affected candidate is marked
`LOAD_FAILED` with the cycle in its `errorMessage`.
