---
name: project-docs
description: Rules for KDoc, README, MkDocs under docs and CHANGELOG.md. Load after any production change to check and adjust the documentation, and before writing or editing README, MkDocs pages or the changelog.
---

# Documentation

## Code

* EVERY public member in EVERY source file (except automatically generated source files) is to be documented with KDoc
* EVERY test method is to be documented with a detailed KDoc describing the use case

## Readme

* There MUST be instructions on how to check out, build and run the project
* EVERY feature must be documented in a bullet point
* There MUST be instructions on how to consume the artifacts
* There MUST be a short outline of the "WHAT" of the project
* There MUST be a reference to the MkDocs documentation (gh-pages), the API documentation and the licence report
* The readme MUST be checked automatically after changes and adjusted if required
* MUST contain the current state of implementation in the form of a list with implemented and planned features with their state

## MkDocs

* MkDocs MUST be integrated under `docs`
* MKDocs contain only user information to interact with the public interface of the application
* Structure:
    * `docs/mkdocs.yml` - Root file
    * `docs/docs` - *.MD files
    * `docs/docs/assets` - Further asset files (MUST reside inside `docs_dir` so that MkDocs ships them)
    * `docs/docs/stylesheets` - Additional CSS files
* The documentation MUST be checked after every change and adjusted if necessary

## CHANGELOG.md

* A change file MUST be present
* It MUST be updated with the applied changes after a change
    * ONLY changes an END USER of the plugin can see or notice in the IDE belong there
    * FORBIDDEN entries: tests of any kind and their coverage, refactorings, renamings, moved code,
      build and CI changes, changes to the rules under `.claude`, changes to the documentation itself
    * If a change produces no such entry, the changelog MUST stay untouched - an entry MUST NOT be invented
      to have written one
* The prescribed format MUST be kept
    * New entries MUST go under `[UNRELEASED]`
