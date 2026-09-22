package org.pcsoft.framework.pluggiat

/**
 * Optional lifecycle hooks a plugin's classes may implement to react to their plugin's load/enable/
 * disable/unload transitions. All hook methods have an empty default implementation, so a class
 * only needs to override the hooks it actually cares about.
 *
 * `onLoad` always runs before `onEnable`, and `onDisable` always runs before `onUnload`. Across a
 * plugin's declared dependencies, every dependency's `onLoad` runs before any dependent's
 * `onEnable` (and symmetrically for teardown) - the two-phase split exists to make this load-order
 * guarantee possible, not to model an enabled/disabled state by itself.
 *
 * If a plugin has more than one class implementing [PluginLifecycle], the call order of their
 * hooks relative to each other is NOT deterministic.
 *
 * `onUnload` is always immediately followed by discarding/closing the plugin's class loader; a
 * later reactivation therefore requires a full reload, never just flipping an enabled flag back on.
 */
interface PluginLifecycle {

    /**
     * Called once the plugin's classes are loaded, before [onEnable].
     */
    fun onLoad() {}

    /**
     * Called once the plugin becomes active, after [onLoad].
     */
    fun onEnable() {}

    /**
     * Called once the plugin becomes inactive, before [onUnload].
     */
    fun onDisable() {}

    /**
     * Called once the plugin is being unloaded, after [onDisable] and immediately before its class
     * loader is discarded/closed.
     */
    fun onUnload() {}
}
