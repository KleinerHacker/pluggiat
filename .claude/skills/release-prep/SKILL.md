---
name: release-prep
description: Where the human-readable version number appears across the pluggiat repository and how CHANGELOG.md must be updated when preparing a release. Load before bumping a version for a release, and before running the release-version-updater agent.
---

# Release Preparation

* The published application version is **not** stored in any file. It is derived at build time from
  the `releaseVersion` project property (`build.gradle.kts`, line ~26), which `release.yml` passes as
  the git tag name (`-PreleaseVersion=${{ github.ref_name }}`). **Never edit this file for a version
  bump.**
* Release preparation only updates the **human-readable** version references below, plus the
  changelog. It never creates the git tag and never touches `.github/workflows`.

## Locations to update

* `CHANGELOG.md`:
    * The current `## [Unreleased]` section becomes `## [<new version>]`.
    * A fresh, empty `## [Unreleased]` section is inserted above it.
    * If `## [Unreleased]` has no entries under it, there is nothing releasable - stop and tell the
      user instead of inventing an entry or releasing an empty version.
* `README.md`: the Gradle and Maven dependency snippets under "Consuming the artifacts".
* `docs/docs/index.md` and `docs/docs/index.de.md`: the Gradle and Maven dependency snippets under
  "Consuming the artifacts" / "Nutzung der Artefakte".
* Any other occurrence of the exact old version string found by a repository-wide search, **except**:
    * anything under a module's `build/` output directory.
    * `CHANGELOG.md` entries for a past, already-released version (those are history and stay as
      they were).

## Determining the old version

The old version is the heading of the most recent released section in `CHANGELOG.md`
(`## [<old version>]`, the first `## [...]` heading after `## [Unreleased]`).

## Verification

* After editing, re-run a repository-wide search for the old version string outside the excluded
  locations above and confirm nothing releasable was missed.
* Follow the project's standing rules (`.claude/CLAUDE.md` and `.claude/rules/*.md`) for everything
  else - plan mode, one edit per file, git usage, German console output - this skill only defines
  *where* the version lives, not how the surrounding workflow runs.
