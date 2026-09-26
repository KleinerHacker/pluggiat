---
name: security-review-changes
description: Rules for re-checking security whenever source code changes - which files must be covered (changed files plus their referenced classes) and how the vulnerability check must proceed. Load whenever source code has been created or changed, to run a fresh security check.
---

# Security Review on Source Code Changes

* Trigger: any creation or change of a source code file (production or test)
* Scope of the check:
  * ALL changed source code files themselves
  * ALL classes referenced by a changed file (imports, supertypes, constructor/property types,
    function parameter and return types, used generic type arguments)
  * Referenced classes are only followed ONE level from the changed file - transitive references
    beyond that level are NOT automatically pulled in
  * Third-party / dependency classes are excluded from the check itself, but their USAGE in the
    changed file (e.g. unsafe API usage, deserialization, reflection, process/command execution,
    file/network access) IS in scope
* For EACH file in scope, check for known vulnerability classes, including but not limited to:
  * Injection (command, path/traversal, deserialization, reflection-based)
  * Broken access control / missing authorization checks
  * Insecure use of cryptography or secrets handling
  * Unsafe handling of untrusted input (plugin code, external data)
  * Resource exhaustion / missing limits on untrusted input
  * Race conditions and unsafe concurrency around security-relevant state
* The check MUST be repeated after every further change to the same or additional files - a
  previous result becomes stale as soon as the file set changes again
* Findings MUST be reported with:
  * File path and line reference
  * Vulnerability class
  * Concrete exploit scenario (what input/state leads to what impact)
  * Suggested fix
* If no vulnerability is found in a file, that file is listed explicitly as checked, not omitted
* This skill does NOT fix vulnerabilities itself and does NOT create a plan - it only reports
  findings; fixing them follows the normal planning and implementation rules
* Console/CLI output produced while applying this skill MUST be in German, per the global rules
