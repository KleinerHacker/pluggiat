---
name: git and GitHub
---

# GIT

* All changes are made through GIT:
    * Rename / move: `git mv`
    * Delete: `git rm`
    * Create: add with `git add` after creation
* Commits, pushes, pulls or any other actions communicating with the Git server MUST NEVER be invoked.
    * Should it be required, the user MUST be asked
* Exceptions:
    * NEVER add plans or plan status

## Target Environment

* GitHub MUST be used
* All files around GitHub reside in `.github`
* Structure of the pipelines is in the `ci-pipeline` skill, which MUST be loaded before a workflow
  file is created or changed and after structural project changes
