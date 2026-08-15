# Task 5 Report: Run the same tests on Node

## Status: DONE

## Exact JS pass/fail counts

Two commands were run. The brief's literal Step 1 command (no filter) picks up all
`commonTest` classes, not just `VimRegexParserTest`:

```
./gradlew -p spike/antlr-kotlin jsNodeTest --console=plain
```
```
66 tests completed, 1 failed
```
The extra 2 tests are `SmokeTest` and `GeneratedParserSmokeTest` (Task 1/2 artifacts, not
part of Task 4's 64-test baseline) — both passed. To get an apples-to-apples number against
Task 4's JVM baseline, I re-ran filtered to the same class Task 4 used:

```
./gradlew -p spike/antlr-kotlin jsNodeTest --tests '*VimRegexParserTest*' --console=plain
```
```
com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.test wider unicode character[js, node] FAILED
    AssertionError at .../kotlin/test/JsImpl.kt:23

64 tests completed, 1 failed

BUILD FAILED in 1s
```

**64 tests total, 63 passed, 1 failed — identical count to JVM.** Re-ran with `--rerun`
after the probe file was added to confirm reproducibility; same 64/63/1 result both times.
JUnit XML at
`spike/antlr-kotlin/build/test-results/jsNodeTest/TEST-jsNodeTest.com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.xml`
(`<testsuite ... tests="64" failures="1" errors="0">`).

## The JVM-vs-JS comparison table

**The JS result is identical to the JVM result.** Only one test differs from a pass, and it
differs from a pass on *both* platforms in the same way:

| Test | JVM | JS | Divergence? |
|---|---|---|---|
| `test wider unicode character` | FAILED (E383) | FAILED (E383) | **No** — identical failure on both |
| all other 63 tests | PASSED | PASSED | No |

Failure message, verbatim, from each platform's JUnit XML:

**JVM** (`TEST-com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.xml`):
```
org.opentest4j.AssertionFailedError: Expecting successful parsing for pattern 🨤 but got E383
	at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)
	at org.junit.jupiter.api.Assertions.fail(Assertions.java:138)
	at kotlin.test.junit5.JUnit5Asserter.fail(JUnitSupport.kt:56)
	at kotlin.test.AssertionsKt__AssertionsKt.fail(Assertions.kt:564)
	...
	at com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.test wider unicode character(VimRegexParserTest.kt:292)
```

**JS/Node** (`TEST-jsNodeTest.com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.xml`):
```
AssertionError: AssertionError: Expecting successful parsing for pattern 🨤 but got E383
	at DefaultJsAsserter.protoOf.fail_zdvzi9(.../kotlin/test/JsImpl.kt:23)
	at DefaultJsAsserter.protoOf.fail_o3vfxl(.../kotlin/test/DefaultJsAsserter.kt:71)
	at <global>.fail(.../kotlin/test/Assertions.kt:564)
	at <global>.assertSuccess(.../VimRegexParserTest.kt:388)
	at VimRegexParserTest.protoOf.test_wider_unicode_character_a2qobd(.../VimRegexParserTest.kt:292)
```

Stack frames differ only in the expected way (JUnit5/JVM machinery vs. Kotlin/JS's
`DefaultJsAsserter`/compiled-JS frames). The **assertion payload is byte-for-byte identical**:
`Expecting successful parsing for pattern 🨤 but got E383`, from the same source line
(`VimRegexParserTest.kt:388`/`292`), for the same input pattern.

## The astral-bug verdict on JS

**Reproduces identically on JS, with the same error code (E383) and the same failure
message as JVM.** This settles Task 4's open question (its Concern #1): the bug is **not**
JVM-specific. Since the JVM root-cause investigation traced this to
`StringCharStream.codePoint()` failing to decode a surrogate pair when it's the *last*
codepoint in the input (returning EOF instead of the real codepoint), and the JS result
shows the exact same observable symptom (`E383`, i.e. `LexerNoViableAltException` →
`BailErrorLexer`) on the exact same input, the most parsimonious explanation is that this
bug lives in commonMain-shared logic (or independently-but-identically-broken expect/actual
implementations) rather than being unique to the JVM `CharStream` implementation.

I did not decompile/inspect the JS-target `StringCharStream` source directly (that would
require digging into antlr-kotlin's Kotlin/JS runtime output, which is out of scope for this
task's time budget) — the verdict here rests on matching *observable behavior* (same error
code, same message, same input, same source line) rather than a second independent
source-level trace. Per the brief's framing, the "worse" outcome (passes on JS, meaning the runtimes genuinely
differ) did not occur: the two runtimes agree, which is the strong positive signal (one bug,
one root cause, one class of upstream fix needed — not two divergent runtime behaviors to
reconcile).

## Performance numbers

Method: `ParsePerformanceProbe.kt` (new file, `spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/ParsePerformanceProbe.kt`),
sibling to `VimRegexParserTest.kt`, not appended to it — keeps Task 4's "64 tests" number
stable. Single `@Test` that parses `\(foo\|bar\)\{2,5}[a-z]*\$` 1000 times via
`VimRegexParser.parse(pattern)`, wrapped in `kotlin.time.measureTime`, result printed via
`println`. No warmup, no JIT priming, no averaging across processes — each run below is one
cold JVM/Node process invocation via `--rerun` (forcing Gradle to re-execute rather than use
cached UP-TO-DATE results). This is intentionally rough, per the brief.

Commands:
```
./gradlew -p spike/antlr-kotlin jvmTest --tests '*ParsePerformanceProbe*' --rerun
./gradlew -p spike/antlr-kotlin jsNodeTest --tests '*ParsePerformanceProbe*' --rerun
```

Two independent runs (each a fresh process, read from the JUnit XML `<system-out>`):

| Run | JVM | JS (Node) | JS:JVM ratio |
|---|---|---|---|
| 1 | 203.8 ms | 249.8 ms | 1.23x |
| 2 | 290.5 ms | 263.5 ms | 0.91x |

Both runs land in the same ballpark (~1x), well under the brief's 10x flag threshold. Given
the noise between the two runs (JVM itself varied 204→291ms run-to-run, i.e. more than the
JVM-vs-JS gap in either single run), I read this as: **there is no evidence of a
meaningful JS-vs-JVM throughput cliff for this workload** — the two single-process
timings should be read as "same order of magnitude," not "JS is reliably X% slower/faster."
For search-as-you-type (recompiling a pattern on every keystroke), ~0.2–0.3ms per single
parse (1000 parses / ~250ms) on either target is well within an interactive budget; this is
a rough spike measurement, not a validated production number (no JIT warmup was allowed to
happen, which would likely make steady-state JVM numbers considerably better and is
representative of neither platform's real steady state).

## Files changed

```
A  spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/ParsePerformanceProbe.kt
```

`vim-engine/`, `src/`, and the root `settings.gradle.kts` untouched (`git diff --stat --
vim-engine src` empty). `VimRegexParserTest.kt` was **not** modified (confirmed via `git
status --short` before commit — only the new probe file appeared).

Per Ruling 7 (`findings/` does not exist yet — created by Task 8), only `spike/antlr-kotlin`
was staged and committed, not `findings`. The brief's Step 2 table (JVM-vs-JS comparison) is
reproduced above in this report instead, for Task 8 to fold into `findings/2026-08-16-antlr-kotlin-gate.md`
when that file is created.

Committed at `681d1d1bb` ("spike: run regex parser tests on Node, record JVM/JS
comparison").

## Self-review findings

- `git status --short` after commit shows only the pre-existing untracked `.omc/`
  (present since session start, not created by this task) — nothing else pending.
- `git diff --stat vim-engine src` is empty; nothing outside `spike/antlr-kotlin/` was
  touched.
- `git show --stat HEAD` confirms exactly one file changed: the new
  `ParsePerformanceProbe.kt` (31 insertions, 0 deletions elsewhere).
- `VimRegexParserTest.kt` was not touched — verified both via the commit diff (it does not
  appear) and by re-running `jvmTest --tests '*VimRegexParserTest*'` after the probe file
  was added: still exactly 64 tests, 63 passed, 1 failed, same failure — confirms the probe
  file addition didn't perturb the existing suite.
- `ParsePerformanceProbe.kt` carries the standard MIT copyright header, copied verbatim
  (modulo year, matching the 2026 header already used in this spike's other new files, e.g.
  `GeneratedParserSmokeTest.kt` uses `2003-2026`) from an existing spike file.
- Ran the full JS suite twice (once unfiltered per the brief's literal Step 1 command, once
  filtered to match Task 4's JVM command) and the performance probe twice per target with
  `--rerun` to force fresh execution rather than relying on Gradle's cached UP-TO-DATE
  results — all runs reproducible.
- Test output is clean: the only FAILED line in either suite is the one expected astral-char
  test; no stray stack traces, no other flakiness observed across repeated runs.

## Concerns for Task 6 (Vimscript differential testing)

1. **The astral-character bug is confirmed platform-agnostic** (same E383, same message, on
   both JVM and JS). Task 6 should treat this as a known, already-triaged divergence
   (bin: runtime/library divergence per Task 4's Fix round 1) rather than re-discovering it —
   any Vimscript differential test that ends its search pattern with an astral-plane
   character (outside the BMP) will hit this on both targets identically. This is not
   something Task 6 needs to chase further; it's closed pending an upstream antlr-kotlin fix.
2. **Performance numbers here are not load-bearing** — they're an order-of-magnitude sanity
   check with no warmup, high run-to-run variance (JVM alone varied ~40% between two runs),
   and a single representative pattern. If Task 6 or later phases need real perf numbers
   (e.g. for the "is this viable for search-as-you-type" question), that needs a proper
   benchmark harness (multiple patterns, warmup iterations, statistical reporting), not this
   spike-quality probe.
3. **The 66-vs-64 test count on unfiltered `jsNodeTest`** is expected (extra `SmokeTest` +
   `GeneratedParserSmokeTest` from Tasks 1–2) and not a concern, but worth noting so nobody
   downstream is surprised by a "66 tests" figure appearing in raw Gradle output alongside
   the "64 tests" figure quoted everywhere else in this phase's reports.

## Report file

`/Users/neshkeev/projects/ideavim/.superpowers/sdd/2026-08-16-antlr-kotlin-gate/task-5-report.md`
