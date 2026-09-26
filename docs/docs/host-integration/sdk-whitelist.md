# SDK whitelist

Every plugin is loaded into its own isolated `PluginClassLoader`, a parent-last class loader that
by default cannot see any host-internal class. `PluginLoader` (see [ClassLoader isolation](../plugin-development/dependencies.md))
selectively opens up exactly the parts of the host's own SDK the host wants plugins to use, via a
list of `SdkWhitelistEntry`:

```kotlin
data class SdkWhitelistEntry(
    val packageName: String,
    val recursive: Boolean = true,
)

val loader = PluginLoader(
    sdkWhitelist = listOf(
        SdkWhitelistEntry("com.myhost.pluginapi"),
        SdkWhitelistEntry("com.myhost.pluginapi.legacy", recursive = false),
    ),
)
```

!!! tip "Security recommendations"

    * Whitelist only what plugins actually need to call - the smallest useful surface, not a whole
      package tree "just in case".
    * Never whitelist a package that itself exposes reflection-capable escape hatches (e.g. one that
      hands back the host's own class loader or an internal collection by reference).
    * Prefer `recursive = false` for a package that mixes plugin-facing API with internal helpers, so
      a new internal class added later does not accidentally become reachable.
    * Review the whitelist whenever the host's own SDK package gains new classes - nothing in the
      framework enforces that only intended types end up on it.

## What belongs on the whitelist

Only the host's own plugin-facing API - typically the interface(s) plugin implementations must
implement (the host-defined `T` a plugin's `ExtensionConfiguration<T>` resolves to), plus any
shared DTOs or utility classes the host explicitly wants plugins to use directly. The whitelist is
never derived by the framework itself; it is entirely up to the host to decide what its own SDK
surface is.

Internal host implementation packages should never be whitelisted - anything not listed here stays
completely unreachable from plugin code, even via reflection.

## What does not need to be whitelisted

JDK platform classes (`java.*`, `javax.*`) are always resolvable through a plugin's class loader,
independent of the whitelist - plugin bytecode unconditionally references core JDK types (starting
with `java.lang.Object`), so these are delegated to the JDK's own platform class loader
unconditionally, before the whitelist is even consulted.

## Matching

* `recursive = true` (the default): `packageName` and all of its sub-packages, at any depth, are
  exposed.
* `recursive = false`: only classes directly inside `packageName` are exposed; sub-packages stay
  invisible.

## Force-load

`PluginLoader.load` performs no security check of its own and is unconditionally callable,
independent of any prior security check outcome - there is no separate "force" parameter or code
path. A host application that decides to load a plugin despite a `SECURITY_PROBLEM` scan result
simply calls `load` like any other plugin:

```kotlin
val result = loader.load(path, manifest)
```

Deciding *whether* this is warranted, and logging that decision (e.g. plugin id and the original
failure reason), is entirely the host application's own responsibility - `PluginLoader` neither
makes that decision nor emits any log entry about it itself (see
[Host approval flow after a `SECURITY_PROBLEM`](security.md#host-approval-flow-after-a-security_problem)).
