package org.pcsoft.framework.pluggiat.classloader.fixtures.internal

/**
 * Stand-in for a host-internal class never exposed to plugins, used by [PluginClassLoaderTest] to
 * verify that non-whitelisted host classes stay unreachable.
 */
class HostInternalMarker
