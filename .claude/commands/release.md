Release preparation for pluggiat.

The argument `$ARGUMENTS` is the **target version** for this release (e.g. `0.5.0`). Its presence
means the user is starting release preparation towards exactly that version - treat every following
step in this turn as part of that release prep, not as an unrelated version-number edit.

If `$ARGUMENTS` is empty, ask the user for the target version before doing anything else.

## Steps

1. Load the `release-prep` skill to know where the version number lives in this repository.
2. Dispatch the `release-version-updater` agent with the target version `$ARGUMENTS`. It determines
   the old version itself from `CHANGELOG.md` and updates every human-readable version reference
   (CHANGELOG, and any other file a repository-wide search of the old version turns up) accordingly.
3. Review the agent's report against the repository's standing rules (`.claude/CLAUDE.md`,
   `.claude/rules/*.md`) - in particular whether a plan should have been created for this change; ask
   the user if unclear.
4. Summarize for the user, in German per the console-output rule: old version, target version, every
   file changed, and anything deliberately left untouched.
5. Do not build, commit, tag or push. That stays a separate, explicit step the user asks for.
