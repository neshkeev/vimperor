# Codebase Maintenance Instructions

Routine maintenance on a randomly chosen part of the codebase: keep an eye on it,
find genuine issues, don't change things for the sake of changing them.

This used to run weekly from `codebaseMaintenance.yml`. That workflow is disabled
along with the rest of JetBrains' automation (see
`.github/workflows-disabled/README.md`), so this is now something you do on
purpose, when asked.

## 1. Pick an area

```bash
# A random Kotlin file
find . -name "*.kt" -not -path "*/build/*" -not -path "*/.gradle/*" | shuf -n 1

# Or pick a module deliberately:
#   vim-engine/src/commonMain/kotlin/com/maddyhome/idea/vim/   the engine, both hosts
#   vim-engine/src/jsMain/  |  vim-engine/src/jvmMain/          per-platform engine code
#   src/main/java/com/maddyhome/idea/vim/                       the IntelliJ plugin
#   vscode-extension/src/jsMain/kotlin/                         the VS Code extension
```

**You are not limited to the file you drew.** It is a starting point; follow the
trail into callers, implementations and tests when it leads somewhere.

Note the layout: `vim-engine` is Kotlin Multiplatform, so the engine lives in
`src/commonMain/kotlin`, **not** `src/main/kotlin`.

## 2. What to check

### Code style and structure

- Kotlin conventions: data classes, sealed classes, `when` expressions
- Naming that follows what is already there
- Explicit imports, not wildcards; no unused ones
- KDoc on public API and on anything whose *reason* is not obvious from the code
- **Copyright years: leave them alone.** An older year is fine. Never mention a
  year bump in a commit message.

There is no formatter task. `ktlintCheck` and `ktlintFormat` do not exist in this
build - ktlint is commented out at `vim-engine/build.gradle.kts:27`. Match the
surrounding file by hand.

### Code quality

- Null safety, safe calls, Elvis
- Meaningful error messages
- Duplication worth extracting; dead code worth deleting
- TODOs and FIXMEs: still relevant, or addressable now?
- Magic numbers and strings that want names
- **`commonMain` cannot use JVM APIs.** `String.format`, `Character`, `java.*`,
  reflection - all compile for the JVM target and break the JS one. This is a
  real and repeated mistake; it is worth grepping for when you are in the engine.
- **Enablement checks**: `injector.enabler.isEnabled()` and
  `Editor.isIdeaVimDisabledHere` before Vim-specific operations, on the plugin side.

### Possible bugs

- Off-by-one, especially in ranges and loops
- Edge cases: empty collections, boundary offsets, empty files, the last line
- Unchecked casts
- Concurrency, on the plugin side; the extension is single-threaded
- Initialisation order. In Kotlin/JS, properties are assigned in declaration
  order - a field read from an `init` block before its declaration is `undefined`,
  with no warning. That has bitten this project.

### Architecture

- Single responsibility; dependencies pointing the right way
- Consistent level of abstraction within a function
- **Does it match Vim's design?** That is the goal, and it is a real review
  question, not a slogan.
- **Does an engine change hold for both hosts?** A fix in `commonMain` that
  assumes an IntelliJ editor is a bug in the extension, and the reverse.

### The boundaries nothing checks

Worth a look whenever maintenance lands in `vscode-extension/`:

- `VsCodeApi.kt` - `external` declarations compiled against nothing. Guarded by
  `checkVsCodeApiDeclarations`, which checks names and kind against `@types/vscode`.
- `src/jsTest/vscode-stub/` - the fake VS Code the tests run against. Written from
  the same reading of the docs as the declarations, so it agrees with them whether
  or not they are right. Every real-window bug so far has been a stub that was
  *more permissive* than VS Code.
- The checked-in KSP registries. Moving an `@ExCommand` class without re-running
  `:vim-engine:kspKotlinJvm` silently unregisters the command.

### Tests

- Is the thing you are reading tested at all? If not, that is often the most
  valuable output of a maintenance pass.
- Do the tests cover edge cases, or only the happy path?
- Are the names descriptive?
- **A bug fix gets a regression test** that would fail against the old code and
  says, in a comment, what it is about.

## 3. Investigate before changing

1. Read the code and understand it
2. Check callers, implementations, tests
3. `git log --oneline <file>` - this repository's commit bodies carry the reasoning
4. `git log upstream/master --oneline <file>` when the question is why IdeaVim did
   it that way
5. Run the tests

## 4. When to change

**Do fix**: clear bugs, logic errors, unused imports, misleading documentation,
violations of an established pattern, performance problems with measurable impact.

**Don't fix**: stylistic preference where the file is already consistent, working
code rewritten in a newer idiom, anything subjective, large refactorings without a
clear benefit.

**When in doubt**: write it up, don't change it.

## 5. Making changes

1. **Focused commits**, one logical change each. Split anything larger - rename,
   then update callers, then add behaviour. This matters.
2. **Explain why in the commit body.** That is where this project keeps its
   reasoning.
3. **Run the tests** for what you touched.

## Commands

Java 21 is required and the build refuses anything else:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
# The IntelliJ plugin and the engine's JVM target
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain

# One class or package - much faster, and the usual thing to want
./gradlew :test --tests "SearchGroupTest" --console=plain

# The VS Code extension: tests, the stub-host smoke test, and both API guards
./gradlew :vscode-extension:test --console=plain

# Just its tests. NOTE: `jsNodeTest` does not accept `--tests`; it runs all of them.
./gradlew :vscode-extension:jsNodeTest --console=plain

# Failures land in XML, not on stdout - a Kotlin/JS test's println is swallowed
grep -o 'testcase name="[^"]*"' vscode-extension/build/test-results/jsNodeTest/*.xml

# The plugin, in a dev IDE
./gradlew runIde
```

Property tests are flaky by nature; check whether a failure relates to your change.

## Reporting

Say what you inspected, what you found, and what you changed - including "nothing,
and here is what I checked", which is a perfectly good result.
