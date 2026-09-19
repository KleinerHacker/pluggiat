# Global Rules

* NEVER EVER save memories!!!
* Create subagents chain IS LIMITED to a DEPTH of one agent - ONLY the top-level session ("main",
  never a subagent) MAY start an agent, of any subagent_type including "fork" and remote/isolated
  ones; resuming an already-running agent via SendMessage is not creation and stays allowed
* In case of running tasks: ALWAYS run tasks with FULL QUALIFIED PATH

## Skills

* The following skills carry binding rules and MUST be loaded in the named situation:
  * `testing` - before a test class is created or changed
  * `project-docs` - after EVERY change, to check README, MkDocs, KDoc and CHANGELOG.md
  * `ci-pipeline` - before a workflow file under `.github` is created or changed, and after
en     structural project changes
  * `release-prep` - before bumping a version for a release
  * `java-tls-certificate` - when a JVM build fails with an SSLHandshakeException / PKIX path
    building error

## Concurrency

* Concurrent or long-running processes (e.g. `build`, `test`, `verifyPlugin`, `koverXmlReport`)
  MUST ALWAYS be executed through an agent (Task tool)
  * NOT through a background command of the shell
  * The agent returns the result; only the result is reported

## Limiting search

* NEVER decompile or reflect on a third-party class
  * If this is required, ask the user first
* NEVER search the local system for third-party dependencies (Gradle/Maven caches, JARs,
  extracted sources, `javap` dumps) to learn their API

## Console / CLI Output

* On Console or in CLI: MUST ALWAYS BE IN GERMAN
* Plans printed on Console MUST ALWAYS BE IN GERMAN

## File Output

* Into files: MUST ALWAYS BE IN ENGLISH
