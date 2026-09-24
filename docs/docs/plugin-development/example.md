# Complete example: a report exporter plugin

This page walks through a single, realistic plugin from start to finish, tying together everything
covered on the previous pages: the manifest, an extension point contribution, a required and an
optional dependency, lifecycle hooks and error handling. It assumes the host offers an `exporters`
extension point similar to the one used as an example throughout this documentation - see
[Host integration: complete example](../host-integration/example.md) for the matching host side of
this exact scenario.

## The scenario

`com.example.report-exporter-plugin` adds a "Report" export format to the host application. It:

* requires `com.example.charting-plugin` to render an embedded chart (fails to load without it),
* optionally integrates with `com.example.watermark-plugin` if present, to stamp a watermark onto
  the generated report,
* keeps an open output stream across calls and needs to release it on shutdown - a good use for
  `PluginLifecycle`,
* wants a failed export to surface as a recoverable error, not to disable the whole plugin.

## The manifest

```yaml
id: com.example.report-exporter-plugin
name: Report Exporter
version: "2.1.0"
minVersion: "1.4.0"
icon: <base64-encoded PNG>
description: Exports data as a formatted PDF/HTML report, with optional watermarking.
author:
  name: Jane Doe
  mail: jane.doe@example.com
links:
  documentation: https://example.com/report-exporter/docs
  sourceCode: https://example.com/report-exporter
legal:
  copyright: "Copyright (c) 2026 Jane Doe"
  license: Apache-2.0
dependencies:
  - id: com.example.charting-plugin
    required: true
  - id: com.example.watermark-plugin
    required: false
extensions:
  exporters:
    - implementation: com.example.report.ReportExporter
      fileExtension: report.html
      displayName: "Report (HTML)"
```

Two things worth noting, both covered on earlier pages:

* `minVersion: "1.4.0"` means this plugin refuses to load against an older host - see
  [Manifest](manifest.md#required).
* The two `dependencies` entries differ only in `required`, which is exactly what determines
  whether a missing `com.example.watermark-plugin` merely disables watermarking or invalidates the
  whole plugin - see [Dependencies](dependencies.md#required-vs-optional).

## The extension implementation

The host's `Exporter` interface (defined by the host, see the host-side example) is implemented as
a class that also implements `PluginLifecycle` for resource management:

```kotlin
package com.example.report

import org.pcsoft.framework.pluggiat.PluginLifecycle
import java.io.OutputStream

class ReportExporter : Exporter, PluginLifecycle {

    private lateinit var renderPool: ReportRenderPool

    override fun onLoad() {
        // Cheap, side-effect-free setup - runs for every dependency before any onEnable runs.
        renderPool = ReportRenderPool(size = 4)
    }

    override fun onEnable() {
        // Safe to do real work here - all of this plugin's required dependencies already ran onLoad.
        renderPool.warmUp()
    }

    override fun export(data: List<Row>): ByteArray {
        val chart = ChartingApi.renderChart(data) // from the required com.example.charting-plugin
        val html = renderPool.render(data, chart)
        return if (WatermarkHelper.isAvailable()) {
            WatermarkHelper.stamp(html) // only touches the optional dependency's types
        } else {
            html
        }
    }

    override fun onDisable() {
        renderPool.drainInFlight()
    }

    override fun onUnload() {
        renderPool.close()
    }
}
```

### Using the required dependency directly

`com.example.charting-plugin` is `required: true`, so its absence already invalidates this plugin
before any of this code ever runs - `ChartingApi` can be referenced directly, exactly like any other
type, with no presence check needed.

### Using the optional dependency through a helper

`com.example.watermark-plugin` is `required: false` and might not be installed. Per the
[helper-class pattern](dependencies.md#helper-class-pattern-for-optional-dependencies),
`WatermarkApi` (the optional dependency's type) is never referenced directly inside `ReportExporter`
itself - only inside the small `WatermarkHelper` object, so `ReportExporter`'s own class file never
needs `WatermarkApi` to resolve:

```kotlin
package com.example.report

internal object WatermarkHelper {
    fun isAvailable(): Boolean = WatermarkPluginRegistry.isLoaded("com.example.watermark-plugin")

    fun stamp(html: ByteArray): ByteArray {
        val api: WatermarkApi = WatermarkApiImpl() // resolved only when this line actually runs
        return api.applyWatermark(html)
    }
}
```

### Why the lifecycle split matters here

`renderPool.warmUp()` happens in `onEnable`, not `onLoad`. If a future version of this plugin added
its own optional dependency on another plugin's `onLoad` output, doing expensive work in `onLoad`
could run before that dependency had a chance to initialize - see
[Lifecycle hooks: call order](lifecycle.md#call-order). Symmetrically, `renderPool.close()` sits in
`onUnload`, not `onDisable`, so any dependent plugin's own `onDisable` still sees a working
`ReportExporter` right up until teardown finishes.

## Error handling in practice

`export` can fail for reasons that should not take the whole plugin down - e.g. malformed input
rows - versus reasons that mean the plugin cannot continue at all - e.g. the render pool failing to
initialize. Following [Error handling](error-handling.md):

```kotlin
override fun export(data: List<Row>): ByteArray {
    if (data.isEmpty()) {
        throw PluginExecutionException("Cannot export an empty report") // IGNORE: plugin stays active
    }
    val pool = renderPool.takeIfHealthy()
        ?: throw PluginFatalException("Render pool is corrupted, cannot recover") // UNLOAD

    return pool.render(data, ChartingApi.renderChart(data))
}
```

A caller on the host side never receives a `ReportExporter` reference directly - only the runtime
enforcement proxy - so any other, unexpected exception `export` might let escape (a `NullPointerException`
from a bug, say) still gets caught, logged, and resolved via the host's configured exception
handling matrix, defaulting to `UNLOAD` for an unchecked exception the plugin did not anticipate.

## Putting it together

1. The host scans the plugin's `.zip`/`.jar` and validates its manifest.
2. `minVersion` and the `com.example.charting-plugin` dependency are checked before any class of
   this plugin is even loaded.
3. `PluginLifecycle.onLoad` runs, then `onEnable` - both before `export` is ever called.
4. Every `export` call goes through the runtime enforcement proxy; `PluginExecutionException` keeps
   the plugin running, `PluginFatalException` (or an unanticipated unchecked exception) unloads it.
5. On host shutdown, or if the plugin is disabled, `onDisable` then `onUnload` run, and the plugin's
   class loader is discarded.
