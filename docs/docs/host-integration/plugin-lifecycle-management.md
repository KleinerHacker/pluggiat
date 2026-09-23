# Plugin lifecycle management

This page covers the host's side of a plugin's lifecycle: enabling/disabling it, and what happens
when a plugin misbehaves at runtime. For the plugin developer's side, see
[Lifecycle hooks](../plugin-development/lifecycle.md) and [Error handling](../plugin-development/error-handling.md).

## Enabled/disabled status

A plugin's enabled/disabled status is stored via the configured `PluginPersistenceStrategy`, under
the key `"enabled"` (`"true"`/`"false"`; absent means enabled). This is checked **before** any
extension entry of a disabled plugin is even mapped - the extension implementation class is never
resolved via reflection, so a disabled plugin's classes are never loaded at all, not even
transitively (parent classes, interfaces, field types of what would have been resolved).

The framework holds no separate, detached enabled/disabled flag anywhere - `PluginPersistenceStrategy.read`
is the single source of truth. Every status change is written through immediately via
`PluginPersistenceStrategy.write`.

## Disable reason

Alongside `"enabled"`, the key `"disabledReason"` records why a plugin was disabled, distinguishing:

* `USER` - a host records this itself for an explicit, user-initiated disable.
* `RUNTIME_ERROR` - the framework records this automatically when an `UNLOAD` action (see below)
  force-disables a plugin.
* `SECURITY_RECHECK_FAILED` - `PluginManager.reactivate` records this when the security re-check on
  reactivation fails.

## Deactivation always discards the class loader

Whenever a plugin is deactivated - user-initiated or forced - its isolated class loader is
discarded/closed as the fixed final step. There is no "soft disable" that keeps classes loaded.
Reactivating the plugin afterward therefore always requires a full reload through `PluginLoader`,
never just flipping the enabled flag back on.

## Reactivation: security re-check first

Reactivating a disabled plugin always re-checks the security chain **before** reloading it - see
[`PluginManager.reactivate`](plugin-manager.md#reactivation). A failed re-check keeps the plugin
disabled and does not fall back to an automatic force-load.

## Runtime error isolation

Every call into a plugin's extension implementation is enforced through a runtime proxy that
resolves escaping exceptions via the configured `ExceptionHandlingStrategy` into one of three
actions - `IGNORE`, `UNLOAD`, `CRASH`. See [Error handling](../plugin-development/error-handling.md)
for the plugin-facing view of this mechanism (recommended exception types, the standard resolution
matrix, and what you see when debugging).

Configuring the strategy:

```kotlin
val strategy = DefaultExceptionHandlingStrategy(
    matrix = mapOf(
        MyDomainException::class to ExceptionHandlingAction.IGNORE,
    ),
    parent = null, // optionally chain to another ExceptionHandlingStrategy
)
```

There is exactly one host-wide strategy, configured via `PluginManagerConfiguration.exceptionHandlingStrategy`
- there is no way to register a per-plugin strategy. A plugin only influences the outcome
indirectly, through the class of the exception it lets escape.

* **`IGNORE`** - the plugin stays active; the incident is only logged.
* **`UNLOAD`** - the plugin is forcibly disabled: its `PluginLifecycle.onDisable`/`onUnload` hooks
  run (if implemented), its class loader is discarded, and it is persisted as disabled with reason
  `RUNTIME_ERROR` - all synchronously, before the resulting `PluginFatalException` reaches the host.
* **`CRASH`** - the exception's stack trace is printed and the whole host JVM is halted
  (`Runtime.getRuntime().halt(...)`). Deliberately not a recommended default for any exception type.

Only the plugin that raised the exception is affected; sibling plugins keep running unaffected by
another plugin's `UNLOAD`.
