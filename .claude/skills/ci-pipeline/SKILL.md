---
name: ci-pipeline
description: Required structure of the GitHub Actions pipelines ci.yml and release.yml under .github. Load before creating or changing any workflow file, and after structural project changes (new module, new Gradle task, new artifact) to check the pipeline.
---

# Target Environment

* GitHub MUST be used
* All files around GitHub reside in `.github`
* For deeper structural changes the pipeline MUST be checked and adjusted if necessary

## Pipeline

* There MUST be a pipeline for the regular build in `ci.yml`
    * It contains: [Build] Build -> Test, [Verify] Licences / Build and verify MkDocs
    * `Build` and `Verify` MUST be processed in parallel; everything within `Verify` MUST also run in parallel
    * This project has no plugin marketplace or plugin-verification step - it is a plain library
* There MUST be a pipeline for a tag based release named `release.yml`
    * It contains: [Changelog] Verify against version -> ([Build] Build -> Test, [Verify] Verify licences, [MkDocs] Build -> Deploy, [Release] Sign and push artifact -> Write release)
    * `Changelog` runs first
    * `Build`, `Verify`, `MkDocs` in parallel afterwards
        * `Verify` internally in parallel as well
    * `Release` at the end
        * Deployment errors MUST be ignored, but shown as a warning
