@AGENTS.md

## Claude Code specifics

Project skills live in `.claude/skills/`; invoke them when the task matches:

- `evolve-public-api`: any change to a public or protected declaration in a published module.
- `add-module`: creating a new Gradle module.
- `verify-changes`: before committing or pushing, and when CI is red.

Shared permissions are in `.claude/settings.json`. Keep personal overrides in
`.claude/settings.local.json`, which is git-ignored.
