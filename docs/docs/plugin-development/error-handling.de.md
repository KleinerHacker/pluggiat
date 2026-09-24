# Fehlerbehandlung

Jeder Aufruf, den der Host in eine Ihrer Erweiterungsimplementierungen macht, läuft über einen
Runtime-Enforcement-Proxy. Sie erhalten vom Host niemals eine Referenz auf die echte
Implementierungsinstanz zurück - nur diesen Proxy, der jedes `Throwable`, das Ihr Code entweichen
lässt, abfängt und in einen von zwei host-seitigen Ausnahmetypen umwandelt.

## Empfohlene Ausnahmen

```kotlin
class PluginExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class PluginFatalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

Diese sind empfohlen, nicht zwingend:

* Werfen Sie `PluginExecutionException` für einen Fehler, der Ihr Plugin nicht deaktivieren sollte
  (z. B. "dieser eine Export ist fehlgeschlagen, bitte erneut versuchen").
* Werfen Sie `PluginFatalException` für einen Fehler, der so schwerwiegend ist, dass Ihr Plugin
  nicht weiterarbeiten kann.

## Standard-Auflösungsmatrix

Werfen Sie etwas anderes, entscheidet die konfigurierte Fehlerbehandlungsstrategie des Hosts, was
passiert. Sofern der Host nichts Eigenes konfiguriert hat, gilt die Standardmatrix:

| Geworfene Ausnahme                                              | Aktion   |
|-------------------------------------------------------------------|--------|
| `PluginExecutionException`                                         | `IGNORE` |
| `PluginFatalException`                                              | `UNLOAD` |
| jede andere Checked Exception (`Exception`, nicht `RuntimeException`) | `IGNORE` |
| jede andere Unchecked Exception/jeder Error (`RuntimeException`, `Error`) | `UNLOAD` |

* `IGNORE` - Ihr Plugin bleibt aktiv; der Vorfall wird nur protokolliert.
* `UNLOAD` - Ihr Plugin wird zwangsweise deaktiviert: `onDisable`/`onUnload` werden aufgerufen,
  sofern Sie [`PluginLifecycle`](lifecycle.md) implementieren, der Classloader Ihres Plugins wird
  verworfen, und es wird als deaktiviert persistiert. Eine spätere Reaktivierung erfordert einen
  vollständigen Reload und eine erneute Sicherheitsprüfung.
* `CRASH` - ein Host kann dies für bestimmte Ausnahmetypen konfigurieren; es hält die gesamte
  Host-JVM an. Bewusst nichts, was ein Plugin jemals absichtlich auslösen sollte.

Ein Host kann eine vollständig eigene Matrix konfigurieren; betrachten Sie diese Tabelle daher als
den Standard, den Sie erhalten, sofern die Dokumentation des Hosts nichts anderes angibt.

## Was Sie beim Debuggen sehen

Da jeder Aufruf über den Proxy läuft, sieht einiges anders aus als ein direkter Aufruf Ihrer Klasse:

* Die Instanz, die der Host hält, ist ein JDK-Dynamic-Proxy (wenn der API-Typ Ihres
  Erweiterungspunkts ein Interface ist) oder eine von ByteBuddy generierte Unterklasse (wenn es
  sich um eine offene Klasse handelt) - nicht Ihre konkrete Implementierungsklasse.
  `instance is YourConcreteClass` ist `false`; prüfen Sie stattdessen gegen den Interface-/API-Typ.
* Jede Ausnahme, die an einen Aufrufer weitergegeben wird, hat Ihre ursprüngliche Ausnahme als
  `cause` - der Proxy verwirft nie den ursprünglichen Stacktrace.
* Referenzgleichheit (`===`) gegen Ihre eigene Instanz sowie `toString()` auf dem vom Host
  gehaltenen Wert spiegeln beide den Proxy wider, nicht Ihr Objekt.
* Stacktraces erhalten zusätzliche Frames vom Interceptor und (bei offenen Klassen) von der
  generierten ByteBuddy-Unterklasse.

## Designregel für API-Typen von Erweiterungspunkten

Ist ein Wert, den Ihre Implementierung von einer auf dem Host-API-Typ des Erweiterungspunkts
deklarierten Methode zurückgibt, selbst proxy-fähig (ein Interface oder eine nicht finale Klasse),
wird er auf dieselbe Weise rekursiv umschlossen - einschließlich Array-Elementen, `Collection`-
Elementen und `Map`-Werten. Ein Host, der einen Erweiterungspunkt-API entwirft, sollte daher für
jeden Typ, der die Plugin-/Host-Grenze überschreitet, proxy-fähige Typen (Interfaces oder explizit
`open` deklarierte Klassen) bevorzugen; eine `final`-Klasse, die diese Grenze überschreitet, verliert
die Durchsetzungsgarantie für auf ihr getätigte Aufrufe (sie wird unverändert durchgereicht, wobei
der Host eine Warnung protokolliert).

## Bekannte Einschränkungen

Der Proxy fängt Aufrufe ab, die über den deklarierten API-Typ des Erweiterungspunkts erfolgen.
Aufgrund der Natur der JVM kann er Folgendes nicht abfangen:

* `final`-Methoden (sie können vom Proxy nicht überschrieben werden).
* Direkten Feldzugriff (Felder werden nie geproxyt, nur Methodenaufrufe).
* `static`-Methoden (es gibt keine Instanz, die geproxyt werden könnte).

Gestalten Sie die Implementierung Ihres Erweiterungspunkts so, dass alles, was der Host aufrufen
soll, über eine überschreibbare Interface-/Open-Class-Methode läuft.
