# Lifecycle-Hooks

Jede Klasse, die Sie als Erweiterungsimplementierung registrieren, kann optional
`org.pcsoft.framework.pluggiat.PluginLifecycle` implementieren, um auf die
Load-/Enable-/Disable-/Unload-Übergänge ihres Plugins zu reagieren:

```kotlin
interface PluginLifecycle {
    fun onLoad() {}
    fun onEnable() {}
    fun onDisable() {}
    fun onUnload() {}
}
```

Alle vier Methoden haben eine leere Standardimplementierung - implementieren Sie nur die, die Sie
tatsächlich benötigen.

## Aufrufreihenfolge

* `onLoad` läuft immer vor `onEnable`.
* `onDisable` läuft immer vor `onUnload`.
* Auf `onUnload` folgt unmittelbar, dass der Host den isolierten Classloader Ihres Plugins verwirft
  - eine erneute Aktivierung danach erfordert einen vollständigen Reload Ihres Plugins, nicht nur
  das Zurücksetzen eines Flags.

Die Zweiphasenaufteilung (`onLoad`/`onEnable` vs. `onDisable`/`onUnload`) existiert, um eine
korrekte Ladereihenfolge über Plugin-Abhängigkeiten hinweg zu ermöglichen: `onLoad` jeder
Abhängigkeit läuft vor `onEnable` jedes davon abhängigen Plugins, und symmetrisch beim Abbau. Sie
ist keine Möglichkeit, "aktiviert" von "deaktiviert" selbst zu unterscheiden - verwenden Sie dafür
den [aktiviert/deaktiviert-Status](../host-integration/plugin-lifecycle-management.de.md).

## Mehrere Implementierungen pro Plugin

Implementieren mehr als eine Klasse Ihres Plugins `PluginLifecycle`, ist die Aufrufreihenfolge
ihrer Hooks **relativ zueinander nicht deterministisch**. Verlassen Sie sich nicht darauf, dass der
Hook einer Implementierung vor oder nach dem einer anderen Implementierung innerhalb desselben
Plugins läuft.

## Wann onUnload läuft

`onUnload` läuft immer, wenn Ihr Plugin deaktiviert wird - egal ob ein Host es explizit deaktiviert
oder ein Laufzeitfehler in einem Ihrer Erweiterungsaufrufe gemäß der konfigurierten
[Fehlerbehandlungsstrategie](error-handling.de.md) des Hosts zu `UNLOAD` führt. Ihre
`onDisable`/`onUnload`-Implementierungen sollten daher auch in einer "etwas ist schiefgelaufen"-
Situation sicher ausführbar sein, nicht nur bei einem sauberen, beabsichtigten Herunterfahren.
