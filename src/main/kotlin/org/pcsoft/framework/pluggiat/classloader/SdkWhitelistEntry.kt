package org.pcsoft.framework.pluggiat.classloader

/**
 * A single package of the host application's SDK, exposed to plugins through their isolated
 * class loader.
 *
 * @property packageName the package to expose, e.g. `"org.pcsoft.framework.myapp.sdk"`
 * @property recursive whether sub-packages of [packageName] are exposed as well (`true`, default),
 * or only classes directly inside [packageName] (`false`)
 */
data class SdkWhitelistEntry(
    val packageName: String,
    val recursive: Boolean = true,
)
