---
name: tests-maintenance
description: Maintains this fork's two test suites - IdeaVim's JVM tests and the VS Code extension's, including the fixture-replay baseline. Reviews disabled tests, documents exclusions, improves readability. Use for periodic test maintenance.
---

# Tests Maintenance

Keep the test suites healthy: review quality, check what is switched off, and make
sure every exclusion says why.

## Scope

**Do:** review test quality and readability, check whether disabled tests can be
re-enabled, document exclusions, replace meaningless test content, and audit the
fixture baseline.

**Don't:** fix bugs in production code, implement features, or refactor the source.
If a disabled test reveals a real bug, report it - fixing it is a different job
with a different commit.

## The two suites

| Suite                        | Where                                  | Run with                                |
|------------------------------|----------------------------------------|-----------------------------------------|
| IdeaVim, JVM                 | `src/test/`, `tests/`                  | `./gradlew :test --tests "..."`          |
| The VS Code extension, JS    | `vscode-extension/src/test/`         | `./gradlew :vscode-extension:jsNodeTest` |

They are not independent. `VimFixtureReplayTest` in the second suite *reads the
first one*: it parses `doTest(keys, before, after)` calls out of
`src/test/**/*.kt` and replays them against the VS Code host. Editing a JVM test
can therefore change what the extension's suite does.

Java 21 is required for either: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

## One logical change per commit

Focused commits, so each can be read and reverted on its own.

✅ Good, one per commit: update one `DIFFERENT` to a specific reason with a
description; add descriptions to three tests that all share one skip reason and
one fix; re-enable one `@Disabled` test that now passes.

❌ Too much: two different reasons changed in one commit; test content *and*
annotations together; several unrelated tests re-enabled at once.

## What to check

### 1. The fixture baseline (`vscode-extension/src/test/fixtures/known-fixture-failures.txt`)

This is the extension's equivalent of a wall of `@Disabled`, and the most
valuable thing in this skill's scope. It currently holds **15 names under 6
headings**, out of 1,049 harvested fixtures - the last heading, "One each",
collecting the ones that share no cause.

- **Re-run and re-read.** `VimFixtureReplayTest` writes the current list, with the
  difference for each entry, to `build/fixture-failures.txt`. Compare it against
  the checked-in baseline.
- **A name that now passes should come off the list.** The harness fails the build
  when a listed fixture passes, so this is usually already forced - but check that
  the *comment* explaining the group is updated too, and that the group is deleted
  when its last entry goes.
- **Never add a name by hand to make the build green.** Every entry belongs to a
  group whose comment says what is actually wrong. An entry without one is the
  thing to catch.
- Four entries once left this list without anything being fixed, because the
  harness was misreading them. When a group's explanation does not survive
  re-reading, suspect the harness before the host.

### 2. Disabled tests (`@Disabled`)

```bash
grep -rn "@Disabled" --include="*.kt" src/test tests/
```

For each: try running it. If it passes, work out what changed and re-enable it
with that explanation. If it fails, make sure the reason is in the annotation. If
it tests a feature that no longer exists, delete it.

### 3. Neovim exclusions (`@TestWithoutNeovim`)

JVM `doTest` tests are verified against Neovim, which is a real oracle and worth
protecting. Tests opted out of it must say why.

```bash
grep -rn "@TestWithoutNeovim" --include="*.kt" src/test tests/
# ...and those with no description, which need one:
grep -rn "@TestWithoutNeovim(SkipNeovimReason\.[A-Z_]*)" --include="*.kt" src/test
```

| Reason | When to use |
|--------|-------------|
| `SEE_DESCRIPTION` | Case-specific difference fitting no other category (description required) |
| `PLUGIN` | Extension-specific behaviour (surround, commentary, ...) |
| `INLAYS` | IntelliJ inlays |
| `OPTION` | IdeaVim-specific option behaviour |
| `UNCLEAR` | **Deprecated** - investigate and replace |
| `NON_ASCII` | Non-ASCII handling differs |
| `MAPPING` | Mapping-specific |
| `SELECT_MODE` | Vim's Select mode |
| `VISUAL_BLOCK_MODE` | Block Visual edge cases |
| `DIFFERENT` | **Deprecated** - use something specific |
| `NOT_VIM_TESTING` | Not testing Vim behaviour (IDE integration) |
| `SHOW_CMD` | `:showcmd` |
| `SCROLL` | Scrolling; the viewport differs |
| `TEMPLATES` | IntelliJ live templates |
| `EDITOR_MODIFICATION` | Editor-specific modification |
| `CMD` | Command-line mode differences |
| `ACTION_COMMAND` | `:action` |
| `FOLDING` | Code folding |
| `TABS` | Tab and window management |
| `PLUGIN_ERROR` | Plugin error handling |
| `VIM_SCRIPT` | Vimscript implementation differences |
| `GUARDED_BLOCKS` | IDE guarded/read-only blocks |
| `CTRL_CODES` | Control codes |
| `BUG_IN_NEOVIM` | A known Neovim bug |
| `PSI` | PSI / code intelligence |
| `IDEAVIM_API_USED` | Uses an API that prevents Neovim state sync |
| `IDEAVIM_WORKS_INTENTIONALLY_DIFFERENT` | Deliberate deviation (evidence required) |
| `INTELLIJ_PLATFORM_INHERITED_DIFFERENCE` | Forced by the IntelliJ Platform |

`IDEAVIM_WORKS_INTENTIONALLY_DIFFERENT` needs **evidence** - a commit message, a
code comment, or an obviously IDE-only feature. Not a guess. Its `description` is
mandatory and must say what differs and why.

`INTELLIJ_PLATFORM_INHERITED_DIFFERENCE` needs a `description` naming the Platform
behaviour that causes it. Common cases: empty buffers (Platform editors can be
empty; Neovim buffers always hold a newline), and offset arithmetic around
newlines.

**Handling the two deprecated reasons:**

1. Remove the annotation and run with Neovim:
   `./gradlew :test -Dnvim --tests "ClassName.testMethodName"`
   Confirm the output contains `NEOVIM TESTING ENABLED`. Without that line the
   test ran *without* Neovim and proves nothing.
2. If it passes, the annotation is stale - delete it.
3. If it fails, read the failure and pick the specific reason, with a description.

### 4. Test quality

```bash
grep -rn "asdf\|qwerty\|xxxxx\|aaaaa\|dhjkw" --include="*.kt" src/test tests/ vscode-extension/src/test
```

Replace with realistic code snippets or the Lorem Ipsum template in
CONTRIBUTING.md. Names should say what is being tested.

Note the extension's own convention: its tests carry a KDoc explaining what the
test is *about* - often what was wrong in a real window, and what Vim does. When
adding one there, match that.

### 5. `@VimBehaviorDiffers`

Check whether the documented difference is still real, and whether
`shouldBeFixed = true` cases can now be aligned with Vim - especially engine-level
ones, since this fork is free to change `vim-engine`.

## Commit messages

```
tests: Re-enable DeleteMotionTest after the caret fix

Disabled for a caret positioning bug fixed in abc123. Verified it passes
consistently.
```

```
tests: Drop three fixtures from the known-failures baseline

The Select-mode caret group is empty now that the caret is built on the far
side of the selection. Removed the group and its explanation with it.
```

## Commands

**`--tests` needs the leading colon.** Bare `./gradlew test --tests "X"` fails
with `Unknown command-line option '--tests'`: `test` matches by name across
projects, and `:vim-engine:test` and `:vscode-extension:test` are aggregator tasks
that take no such option. `:test` is the root project's real test task.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./gradlew :test --tests "ClassName.testMethod" --console=plain
./gradlew :test -Dnvim --tests "ClassName" --console=plain  # look for NEOVIM TESTING ENABLED
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain

./gradlew :vscode-extension:jsNodeTest --console=plain      # no --tests; runs all of them
```

A Kotlin/JS test's `println` does not reach the console. Read results from
`vscode-extension/build/test-results/jsNodeTest/*.xml`, or make the assertion
carry what you wanted to see.

## Reporting

Say what you checked, what you found, and what you changed. "Everything checked
out" is a fine result and worth writing down.
