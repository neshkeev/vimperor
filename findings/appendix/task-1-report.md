# Task 1 Report: Standalone KMP build with jvm and js targets

## What I implemented

Created a standalone Gradle build under `spike/antlr-kotlin/`, invisible to the root build (not referenced from root `settings.gradle.kts`, no changes to `vim-engine/` or `src/`):

- `spike/antlr-kotlin/settings.gradle.kts` — as specified in the brief, plus the foojay toolchain resolver plugin per the team lead's ruling (mirrors root `settings.gradle.kts:24-27`), so JDK 21 auto-provisions if not found locally rather than relying on "probably" auto-detection.
- `spike/antlr-kotlin/build.gradle.kts` — exactly as specified in the brief: Kotlin Multiplatform plugin 2.3.20, `jvmToolchain(21)`, `jvm()` and `js { nodejs() }` targets, `kotlin.test` in `commonTest`, `kotlin.test-junit5` in `jvmTest`, JUnit Platform wired for the `Test` tasks. No ANTLR anywhere.
- `spike/antlr-kotlin/src/commonTest/kotlin/SmokeTest.kt` — the brief's `SmokeTest` (with the standard IdeaVim MIT copyright header, copied verbatim from the root `settings.gradle.kts` header, since this is a new `.kt` file).
- `spike/antlr-kotlin/.gitignore` — added `.gradle/` and `kotlin-js-store/`. Not in the brief, but necessary: root `.gitignore` ignores `**/build/` and unanchored `.kotlin`, but its `/.gradle/` pattern is anchored to the repo root only, so `spike/antlr-kotlin/.gradle/` (the Gradle project cache, ~1MB of binary files) and the Yarn-generated `kotlin-js-store/` (lockfile, not needed for a throwaway spike) would otherwise have been swept in by the brief's literal `git add spike/antlr-kotlin`. Verified via `git status --porcelain` that only the 4 intended files ended up staged.

## What I tested and the results

Command used throughout: `./gradlew -p spike/antlr-kotlin <tasks> --console=plain`.

### Evidence for Step 4 (deliberate failure on both targets)

Command: `./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest --console=plain --continue`

(First attempt without `--continue` only showed the `jvmTest` failure because Gradle stopped before reaching `jsNodeTest` — re-ran with `--continue` to force both to execute and confirm both failed.)

Relevant output:

```
> Task :jvmTest FAILED

SmokeTest[jvm] > this test is expected to fail on first run()[jvm] FAILED
    org.opentest4j.AssertionFailedError at SmokeTest.kt:20

2 tests completed, 1 failed
...
SmokeTest.this test is expected to fail on first run[js, node] FAILED
    AssertionError at .../build/compileSync/js/test/testDevelopmentExecutable/kotlin/js/src/main/kotlin/kotlin/test/JsImpl.kt:23

> Task :jsNodeTest FAILED

2 tests completed, 1 failed

FAILURE: Build completed with 2 failures.
1: Execution failed for task ':jvmTest' ...
2: Execution failed for task ':jsNodeTest' ...
BUILD FAILED in 14s
```

This is exactly the expected evidence: the same deliberate assertion failure (`"js-or-jvm"` vs `"deliberate-failure"`) surfaced independently under both the JVM test runner (JUnit 5, via `org.opentest4j.AssertionFailedError`) and the JS test runner (Node/Mocha via kotlin.test's JS shim, `AssertionError`), each reporting "2 tests completed, 1 failed" — proving both runners actually execute tests rather than one being silently skipped or no-op.

The first `jsNodeTest` run downloaded a Node distribution and Yarn as expected (visible as `kotlinNodeJsSetup`, `kotlinYarnSetup`, `kotlinNpmInstall` tasks); this took a couple of minutes and was not a hang.

### Step 5

Deleted the `this test is expected to fail on first run` function from `SmokeTest.kt`, leaving only `toolchain runs tests on this platform`.

### Evidence for Step 6 (green build)

Command: `./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest --console=plain`

```
> Task :jvmTest
> Task :jsTestTestDevelopmentExecutableCompileSync
> Task :jsNodeTest

BUILD SUCCESSFUL in 1s
19 actionable tasks: 9 executed, 10 up-to-date
```

Both `jvmTest` and `jsNodeTest` passed cleanly.

## Files changed

- `spike/antlr-kotlin/settings.gradle.kts` (new)
- `spike/antlr-kotlin/build.gradle.kts` (new)
- `spike/antlr-kotlin/src/commonTest/kotlin/SmokeTest.kt` (new)
- `spike/antlr-kotlin/.gitignore` (new, not in brief — see above)

Commit: `01d792fdc` — "spike: standalone KMP build with jvm and js targets"

## Self-review findings

- Confirmed `build.gradle.kts` and `settings.gradle.kts` contain only what the brief specifies plus the mandated foojay resolver addition — no extra dependencies, no ANTLR, no gold-plating.
- Confirmed `SmokeTest.kt` matches the brief's final (post-Step-5) content plus only the required copyright header.
- Confirmed via `git status --porcelain` and `git diff --cached` before committing that exactly 4 files were staged, matching what's described above — no `.gradle/` cache, no `build/`, no `kotlin-js-store/` lockfile got swept into the commit.
- Test output for the passing run is clean (no stray warnings beyond normal Gradle/Node bootstrap noise, which does not appear on the cached green run in Step 6).
- Nothing modified outside `spike/antlr-kotlin/`; root `settings.gradle.kts`, `vim-engine/`, and `src/` are untouched.

## Issues or concerns for later tasks

- The `git add spike/antlr-kotlin` instruction in the brief, taken completely literally, would have committed the local `.gradle/` build cache (~1MB of binary files) and a Yarn `kotlin-js-store/yarn.lock`, because root `.gitignore`'s `/.gradle/` pattern is anchored to the repo root and doesn't cover a nested `.gradle/` directory inside a subproject. I added a small `.gitignore` scoped to `spike/antlr-kotlin/` to prevent this. Future tasks in this spike that also do `git add spike/antlr-kotlin` should be fine now that this `.gitignore` exists, but should double check `git status` before committing regardless.
- Kotlin/JS toolchain works cleanly in this environment: Gradle 9.6.1 (root wrapper, reused via `-p`), Kotlin Multiplatform plugin 2.3.20, JDK 21 toolchain (resolved via the foojay plugin — did not need to investigate whether it found the pre-installed Corretto 21.0.8 or downloaded its own, either way it worked), and Node/Yarn auto-provisioning via the Kotlin/JS Gradle plugin all worked without any manual intervention or environment-specific workarounds. First `jsNodeTest` run took a few minutes for the Node/Yarn download; subsequent runs were fast (~1s, up-to-date).
- No blockers. This clears the way for Task 2 to introduce antlr-kotlin as the next isolated variable.
