# Error handling

Every call the host makes into one of your extension implementations goes through a runtime
enforcement proxy. You never receive a reference to the real implementation instance back from the
host - only this proxy, which catches every `Throwable` your code lets escape and turns it into one
of two host-facing exception types.

## Recommended exceptions

```kotlin
class PluginExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class PluginFatalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

These are recommended, not mandatory:

* Throw `PluginExecutionException` for a failure that should not disable your plugin (e.g. "this
  particular export failed, try again").
* Throw `PluginFatalException` for a failure so severe your plugin cannot continue operating.

## Standard resolution matrix

If you throw anything else, the host's configured error handling strategy decides what happens.
Unless the host configured something custom, the standard matrix applies:

| Exception thrown                                    | Action   |
|-------------------------------------------------------|--------|
| `PluginExecutionException`                             | `IGNORE` |
| `PluginFatalException`                                  | `UNLOAD` |
| any other checked exception (`Exception`, not `RuntimeException`) | `IGNORE` |
| any other unchecked exception/error (`RuntimeException`, `Error`) | `UNLOAD` |

* `IGNORE` - your plugin stays active; the incident is only logged.
* `UNLOAD` - your plugin is forcibly disabled: `onDisable`/`onUnload` are called if you implement
  [`PluginLifecycle`](lifecycle.md), your plugin's class loader is discarded, and it is persisted as
  disabled. Reactivating it afterward requires a full reload and a fresh security check.
* `CRASH` - a host can configure this for specific exception types; it halts the whole host JVM.
  Deliberately not something a plugin should ever expect to trigger on purpose.

A host can configure a completely custom matrix, so treat this table as the default you get unless
told otherwise by the host's own documentation.

## What you see when debugging

Since every call goes through the proxy, a few things look different from calling your class
directly:

* The instance the host holds is a JDK dynamic proxy (if your extension point's API type is an
  interface) or a ByteBuddy-generated subclass (if it is an open class) - not your concrete
  implementation class. `instance is YourConcreteClass` is `false`; check against the interface/API
  type instead.
* Any exception you see re-thrown to a caller has your original exception as its `cause` - the
  proxy never discards the original stack trace.
* Reference equality (`===`) against your own instance, and `toString()` on the value the host
  holds, both reflect the proxy, not your object.
* Stack traces gain extra frames from the interceptor and (for open classes) from the generated
  ByteBuddy subclass.

## Design rule for extension point API types

If a value your implementation returns from a method declared on the extension point's host API
type is itself proxy-eligible (an interface, or a non-final class), it gets wrapped the same way,
recursively - including array elements, `Collection` elements and `Map` values. A host designing an
extension point API should therefore favor proxy-eligible types (interfaces, or explicitly `open`
classes) for every type that crosses the plugin/host boundary; a `final` class crossing that
boundary loses the enforcement guarantee for calls made on it (it is passed through unchanged,
with a warning logged by the host).

## Known limitations

The proxy intercepts calls made through the extension point's declared API type. It cannot, by the
nature of the JVM, intercept:

* `final` methods (they cannot be overridden by the proxy).
* Direct field access (fields are never proxied, only method calls).
* `static` methods (there is no instance to proxy).

Design your extension point implementation so that everything the host is meant to call goes
through an overridable interface/open-class method.
