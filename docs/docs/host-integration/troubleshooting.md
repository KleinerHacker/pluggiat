# Troubleshooting

pluggiat logs through SLF4J (`org.slf4j`); plug in any binding your application already uses. This
page lists the log level convention across the framework, and the error/conflict cases you are most
likely to see reported.

## Log level overview

* **`INFO`** - scan start per location (mode, builtin/external, security chain), a plugin
  successfully loaded, a permanent enabled/disabled change persisted via `PluginPersistenceStrategy`
  (`reload`, `unload`).
* **`WARN`** - an invalid manifest, a failed security strategy/chain (`SECURITY_PROBLEM`), an id
  collision between locations, an exclusive extension point conflict, a force-load of a plugin that
  failed its security check, a persisted checksum via `write<ChecksumSecurityStrategy>`, a persisted
  generic security exception (`forceLoad(..., persistException = true)`) and every subsequent check
  skipped because of it, use of `NoPersistenceStrategy`, a non-proxyable custom return type, an
  `ExceptionHandlingAction.CRASH` about to be carried out.
* **`DEBUG`** - a plugin's contained extensions (key, implementation class), a decorator instantiating
  an `implementation` class, lifecycle transitions (`onLoad`/`onEnable`/`onDisable`/`onUnload`).
* **`ERROR`** - a plugin forcibly disabled by `ExceptionHandlingAction.UNLOAD`, a cyclic plugin
  dependency detected during `PluginManager.scan()` (no candidate of that scan is loaded).
* **`TRACE`** - fine-grained detail within a single security strategy check (signature entry
  verification, checksum computation, chain evaluation order).

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

Only `MANIFEST_NOT_FOUND`/`MANIFEST_INVALID` clear `manifest` - every other non-`LOADED` status keeps
it, so a host can inspect the candidate's declared id/version and, if it decides to, still
`PluginManager.forceLoad` it.

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
contribute to its key, **both plugins are rejected in full** (not just the individual registration) -
logged as a `WARN` naming both plugin ids and the key. This is a conflict of the concrete installed
plugin combination, not a bug in the host's extension point declaration.

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
