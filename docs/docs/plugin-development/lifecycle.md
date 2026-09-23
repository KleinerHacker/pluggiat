# Lifecycle hooks

Any class you register as an extension implementation may optionally implement
`org.pcsoft.framework.pluggiat.PluginLifecycle` to react to its plugin's load/enable/disable/unload
transitions:

```kotlin
interface PluginLifecycle {
    fun onLoad() {}
    fun onEnable() {}
    fun onDisable() {}
    fun onUnload() {}
}
```

All four methods have an empty default implementation - implement only the ones you actually need.

## Call order

* `onLoad` always runs before `onEnable`.
* `onDisable` always runs before `onUnload`.
* `onUnload` is immediately followed by the host discarding your plugin's isolated class loader -
  reactivation afterward requires a full reload of your plugin, not just a flag being flipped back
  on.

The two-phase split (`onLoad`/`onEnable` vs. `onDisable`/`onUnload`) exists to make a correct load
order across plugin dependencies possible: every dependency's `onLoad` runs before any dependent's
`onEnable`, and symmetrically for teardown. It is not a way to distinguish "enabled" from
"disabled" by itself - use [enabled/disabled status](../host-integration/plugin-lifecycle-management.md)
for that.

## Multiple implementations per plugin

If more than one of your plugin's classes implements `PluginLifecycle`, the call order of their
hooks **relative to each other is not deterministic**. Do not rely on one implementation's hook
running before or after another implementation's hook within the same plugin.

## When onUnload runs

`onUnload` runs whenever your plugin is disabled - whether a host explicitly disables it, or a
runtime error in one of your extension calls resolves to `UNLOAD` under the host's configured
[error handling strategy](error-handling.md). Your `onDisable`/`onUnload` implementations should
therefore be safe to run in a "something went wrong" situation, not just on a clean, intentional
shutdown.
