# Plugin-Lifecycle-Verwaltung

Diese Seite behandelt die host-seitige Sicht auf den Lifecycle eines Plugins: das Aktivieren/
Deaktivieren sowie das Verhalten, wenn sich ein Plugin zur Laufzeit fehlerhaft verhält. Für die
Sicht des Plugin-Entwicklers siehe [Lifecycle-Hooks](../plugin-development/lifecycle.md) und
[Fehlerbehandlung](../plugin-development/error-handling.md).

## Aktiviert/deaktiviert-Status

Der Aktiviert/deaktiviert-Status eines Plugins wird über die konfigurierte
`PluginPersistenceStrategy` unter dem Schlüssel `"enabled"` gespeichert (`"true"`/`"false"`;
Fehlen bedeutet aktiviert). Dies wird geprüft, **bevor** überhaupt ein Erweiterungseintrag eines
deaktivierten Plugins gemappt wird - die Implementierungsklasse der Erweiterung wird nie per
Reflection aufgelöst, sodass die Klassen eines deaktivierten Plugins nie geladen werden, nicht
einmal transitiv (Oberklassen, Interfaces, Feldtypen dessen, was ansonsten aufgelöst worden wäre).

Das Framework hält nirgendwo ein separates, losgelöstes Aktiviert/deaktiviert-Flag -
`PluginPersistenceStrategy.read` ist die einzige Quelle der Wahrheit. Jede Statusänderung wird
sofort über `PluginPersistenceStrategy.write` durchgeschrieben.

## Deaktivierungsgrund

Neben `"enabled"` zeichnet der Schlüssel `"disabledReason"` auf, warum ein Plugin deaktiviert
wurde, mit folgenden Unterscheidungen:

* `USER` - ein Host zeichnet dies selbst für eine explizite, benutzerinitiierte Deaktivierung auf.
* `RUNTIME_ERROR` - das Framework zeichnet dies automatisch auf, wenn eine `UNLOAD`-Aktion (siehe
  unten) ein Plugin zwangsweise deaktiviert.
* `SECURITY_RECHECK_FAILED` - `PluginManager.reactivate` zeichnet dies auf, wenn die
  Sicherheits-Neuprüfung bei der Reaktivierung fehlschlägt.

## Deaktivierung verwirft immer den Classloader

Wird ein Plugin deaktiviert - benutzerinitiiert oder erzwungen -, wird sein isolierter Classloader
als fester letzter Schritt verworfen/geschlossen. Es gibt kein "sanftes Deaktivieren", das Klassen
geladen lässt. Eine anschließende Reaktivierung des Plugins erfordert daher immer einen
vollständigen Reload über `PluginLoader`, niemals nur das Zurücksetzen des Aktiviert-Flags.

## Reaktivierung: zuerst die Sicherheits-Neuprüfung

Die Reaktivierung eines deaktivierten Plugins prüft immer zuerst die Sicherheitskette erneut -
siehe [`PluginManager.reactivate`](plugin-manager.md#reaktivierung-reactivate). Eine
fehlgeschlagene Neuprüfung hält das Plugin deaktiviert und fällt nicht auf ein automatisches
Force-Load zurück.

## Laufzeitfehlerisolation

Jeder Aufruf in die Erweiterungsimplementierung eines Plugins wird über einen Runtime-Proxy
durchgesetzt, der entweichende Ausnahmen über die konfigurierte `ExceptionHandlingStrategy` in eine
von drei Aktionen auflöst - `IGNORE`, `UNLOAD`, `CRASH`. Siehe
[Fehlerbehandlung](../plugin-development/error-handling.md) für die plugin-seitige Sicht dieses
Mechanismus (empfohlene Ausnahmetypen, die Standard-Auflösungsmatrix und was Sie beim Debuggen
sehen).

Konfiguration der Strategie:

```kotlin
val strategy = DefaultExceptionHandlingStrategy(
    matrix = mapOf(
        MyDomainException::class to ExceptionHandlingAction.IGNORE,
    ),
    parent = null, // optional an eine weitere ExceptionHandlingStrategy verketten
)
```

Es gibt genau eine host-weite Strategie, konfiguriert über
`PluginManagerConfiguration.exceptionHandlingStrategy` - es gibt keine Möglichkeit, eine
Strategie pro Plugin zu registrieren. Ein Plugin beeinflusst das Ergebnis nur indirekt, über die
Klasse der Ausnahme, die es entweichen lässt.

* **`IGNORE`** - das Plugin bleibt aktiv; der Vorfall wird nur protokolliert.
* **`UNLOAD`** - das Plugin wird zwangsweise deaktiviert: seine
  `PluginLifecycle.onDisable`/`onUnload`-Hooks laufen (sofern implementiert), sein Classloader wird
  verworfen, und es wird mit dem Grund `RUNTIME_ERROR` als deaktiviert persistiert - alles synchron,
  bevor die resultierende `PluginFatalException` den Host erreicht.
* **`CRASH`** - der Stacktrace der Ausnahme wird ausgegeben und die gesamte Host-JVM wird angehalten
  (`Runtime.getRuntime().halt(...)`). Bewusst kein empfohlener Standard für irgendeinen
  Ausnahmetyp.

Nur das Plugin, das die Ausnahme ausgelöst hat, ist betroffen; andere Plugins laufen unbeeinflusst
von der `UNLOAD`-Behandlung eines anderen Plugins weiter.
