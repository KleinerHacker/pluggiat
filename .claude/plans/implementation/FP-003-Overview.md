# Overview: FP-003 GraalVM Native-Image-Kompatibilitaet

* Feature Plan: GraalVM Native-Image-Kompatibilitaet -
  `.claude/plans/features/FP-003-GraalVmNativeImageCompatibility.md`
* IP-01 - Reachability-Metadata fuer Manifest-Parsing -
  `FP-003-IP-01-ReachabilityMetadataManifestParsing.md` (keine Abhaengigkeit)
* IP-02 - Laufzeit-Guard gegen nicht unterstuetzte Modi -
  `FP-003-IP-02-NativeImageRuntimeGuard.md` (keine Abhaengigkeit)
* IP-03 - CI-Verifikation & Dokumentation -
  `FP-003-IP-03-CiVerificationAndDocumentation.md` (abhaengig von IP-01, IP-02)
* Reihenfolge: IP-01 und IP-02 in beliebiger Reihenfolge oder parallel, danach IP-03.
