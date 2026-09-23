# Dependencies

A plugin declares dependencies on other plugins, by their manifest `id`, under `dependencies` (see
[Manifest](manifest.md)):

```yaml
dependencies:
  - id: some-other-plugin
    required: true
  - id: yet-another-plugin
    required: false
```

## Required vs. optional

* `required: true` - if the dependency cannot be resolved (not scanned, invalid, or not visible per
  the effective `PluginDependencyStrategy`, see below), this plugin is reported as invalid and is
  not loaded at all.
* `required: false` - if the dependency cannot be resolved, this plugin loads anyway, simply
  without that dependency's classes being visible to it.

A dependency cycle (through `required` or `optional` edges alike) is always rejected, regardless of
whether every edge in the cycle is optional.

## Visibility

A plugin only ever sees the classes of a dependency it explicitly declared - there is no implicit
or transitive visibility onto any other loaded plugin. Two independent conditions must both hold for
plugin B to see plugin A's classes:

1. B's manifest declares a dependency on A's id.
2. The effective `PluginDependencyStrategy` of B's plugin location allows visibility onto A's
   plugin location (see below) - by default every location can see every other location, but a
   host can restrict this.

### `PluginDependencyStrategy`

Configured globally, optionally overridden per `PluginLocation` via `dependencyStrategyOverride`,
mirroring how [security strategies](../host-integration/security.md) are configured:

```kotlin
interface PluginDependencyStrategy {
    fun isVisible(from: Path, to: Path): Boolean
}
```

A location can always see plugins within itself, regardless of the configured strategy - the only
exception is `DisallowPluginDependencyStrategy`, which suppresses dependencies entirely, even
between plugins of the same location.

| Strategy | Behaviour |
|---|---|
| `UnrestrictedPluginDependencyStrategy` | Default. Every location can see every other location. |
| `LocationPluginDependencyStrategy(allowedLocations)` | A location sees itself plus the explicitly configured `allowedLocations`. |
| `DisallowPluginDependencyStrategy` | No plugin dependencies at all, not even within the same location. |

## Helper-class pattern for optional dependencies

A plain `if` guard around a missing optional dependency's types is not enough: while an unreached
branch usually never triggers class loading, that only holds as long as the missing type is not
also used as a superclass/interface, field type, or method signature type of the plugin's *own*
class - in those cases the JVM needs the missing type just to load and verify the plugin's class
itself, regardless of whether the guarded branch ever runs, causing a `NoClassDefFoundError` well
before the `if` is even evaluated.

The fix is to push every direct use of an optional dependency's types into its own helper class,
loaded only from inside the guarded branch:

```kotlin
// BAD: references the optional dependency's type directly in this class's own signature
class MyExtension {
    fun onLoad() {
        if (isOtherPluginPresent()) {
            val other: OtherPluginApi = OtherPluginApiImpl() // OtherPluginApi type is resolved when MyExtension itself loads
            other.doSomething()
        }
    }
}

// GOOD: the optional dependency's type never appears in MyExtension's own signature
class MyExtension {
    fun onLoad() {
        if (isOtherPluginPresent()) {
            OptionalIntegrationHelper.doSomething() // OtherPluginApi is only resolved once this line runs
        }
    }
}

// only this small class references OtherPluginApi - it is only loaded when actually called
internal object OptionalIntegrationHelper {
    fun doSomething() {
        val other: OtherPluginApi = OtherPluginApiImpl()
        other.doSomething()
    }
}
```

`isOtherPluginPresent()` is typically a simple boolean check, e.g. against a registry the host
application maintains, and never itself needs to reference the optional dependency's types.
