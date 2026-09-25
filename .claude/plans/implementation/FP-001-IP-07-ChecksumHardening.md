# Implementierungsplan: Checksum-/Signatur-Härtung (Byte-Pinning)

Feature Plan: [FP-001-PluginRuntimeSandbox](../features/FP-001-PluginRuntimeSandbox.md), IP-07

## Aufgabe 1: `PinnedPluginContent`

- [ ] `PinnedPluginContent`-Typ erstellen (`ByteArray` bzw. `Map<String, ByteArray>`)
- [ ] Einmaliges Lesen der Kandidatenbytes in `PluginScanner.applySecurityCheck` implementieren

## Aufgabe 2: Sicherheitsstrategie-Anpassung

- [ ] `PluginSecurityStrategy.check`-Überladung für `PinnedPluginContent` ergänzen
- [ ] `ChecksumSecurityStrategy` auf `PinnedPluginContent` umstellen
- [ ] `String.equals` durch `MessageDigest.isEqual` ersetzen
- [ ] `SignatureSecurityStrategy` auf `PinnedPluginContent` umstellen

## Aufgabe 3: Gemeinsame Zip/Jar-Auflösung

- [ ] Hilfsmodul `org.pcsoft.framework.pluggiat.classloader.jar` anlegen
- [ ] `resolveJarEntries`-Funktion implementieren (deterministisch bei doppelten Namen)
- [ ] `SignatureSecurityStrategy` auf `resolveJarEntries` umstellen

## Aufgabe 4: Zertifikats-Gültigkeitsprüfung

- [ ] `certificate.checkValidity()`-Aufruf in `SignatureSecurityStrategy` ergänzen
- [ ] `CertificateExpiredException`/`CertificateNotYetValidException` als `Failure` behandeln

## Aufgabe 5: Speicherbasierter Classloader

- [ ] `PinnedPluginClassLoader`-Klasse erstellen
- [ ] `findClass` über `defineClass` aus gepinnten Bytes implementieren
- [ ] `findResource` aus gepinnten Bytes implementieren
- [ ] `resolveJarEntries` für Klassen-/Ressourcenauflösung verwenden

## Aufgabe 6: Loader-/Scan-Integration

- [ ] `PluginScanResult` um `PinnedPluginContent`-Feld erweitern
- [ ] `PluginLoader.load`-Überladung für `PinnedPluginContent` ergänzen
- [ ] `PluginManager.scan()`/`reactivate()` auf gepinnten Pfad umstellen

## Aufgabe 7: Tests

- [ ] `testing`-Skill laden
- [ ] Test für Erkennung ausgetauschter Bytes zwischen Prüfung und Laden schreiben
- [ ] Test für zeitkonstanten Digestvergleich schreiben
- [ ] Test für doppelte ZIP-Eintragsnamen (Verifikation = Laden) schreiben
- [ ] Test für abgelaufenes Zertifikat schreiben
- [ ] Bestehende `PluginLoaderTest`/`PluginLoaderTestFixtures` anpassen

## Aufgabe 8: Build und Dokumentation

- [ ] Gradle-Ziel `build` ausführen (über Agent)
- [ ] `project-docs`-Skill laden und README/MkDocs/CHANGELOG.md prüfen
- [ ] Verhaltensänderung bei abgelaufenem Zertifikat im CHANGELOG.md vermerken
