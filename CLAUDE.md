# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this repository is

A **hard fork of IdeaVim** being turned into **Vimperor**, a VS Code extension,
without giving up the IntelliJ plugin. Three parts:

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `src/main/java/`    | The IntelliJ plugin (IdeaVim)     | JVM               |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |

`vim-engine` is Kotlin Multiplatform: the engine is in `src/commonMain/kotlin`,
**not** `src/main/kotlin`. **A change to `commonMain` changes both hosts.**

There is no upstream to contribute to. `vim-engine` may be changed freely, and
should be when the bug is the engine's. The `upstream` remote is read-only.

## Quick Reference

**Java 21 is required; the build refuses anything else:**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
# One class or package - the usual thing to want
./gradlew :test --tests "SearchGroupTest" --console=plain

# The standard suite: both hosts, since `test` matches by task name across projects
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain

# The extension alone: its tests, the stub-host smoke test, and both API guards
./gradlew :vscode-extension:test --console=plain

# The plugin, in a dev IDE
./gradlew runIde

# The extension, as a .vsix. See vscode-extension/PUBLISHING.md
./gradlew :vscode-extension:packageExtension
```

Avoid running all tests - it takes too long. Prefer a specific test.

Use `--console=plain` for gradle.

**Two traps in those commands.** `--tests` needs the leading colon: bare
`./gradlew test --tests "X"` fails with `Unknown command-line option '--tests'`,
because `test` matches by name across projects and `:vim-engine:test` and
`:vscode-extension:test` are plain aggregator tasks. `:test` is the root project's
real test task. And `jsNodeTest` accepts no `--tests` at all - it runs every JS
test - while a Kotlin/JS test's `println` never reaches the console, so read
`vscode-extension/build/test-results/jsNodeTest/*.xml` instead.

See CONTRIBUTING.md for the plugin's architecture and
`vscode-extension/DEVELOPMENT.md` for the port's.

## Notes

- Property tests can be flaky - check whether a failure relates to your change
- Use `<Action>` in mappings, not `:action`
- Config file: `~/.ideavimrc` (XDG supported); Vimperor reads the same file
- Goal: match Vim's functionality and architecture
- `commonMain` cannot use JVM APIs (`String.format`, `Character`, `java.*`,
  reflection). They compile for the JVM target and break the JS one.
- Moving or adding an `@ExCommand` class needs `:vim-engine:kspKotlinJvm` re-run
  and the generated JSON committed, or the command is silently unregistered.

## Issue tracking

There isn't one. `VIM-XXXX` tickets belong to JetBrains' YouTrack and are not this
fork's to close; no GitHub issues have been filed on `neshkeev/vimperor`. Bugs are
recorded in commit bodies, in comments at the code, and in
`vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt`.

## Commit messages

A subject that says what changed, in plain words. **No ticket prefix.** The body
carries the reasoning - what was wrong, what Vim does, what was measured. See the
`git-workflow` skill.

## Automation

Every workflow inherited from JetBrains is disabled by living in
`.github/workflows-disabled/` rather than `.github/workflows/`. Read the README
there before moving any back.

There is one live workflow, written for this fork:
`.github/workflows/publish-vimperor.yml`, which packages and publishes the VS
Code extension on a `vimperor-v*` tag. See `vscode-extension/PUBLISHING.md`.
