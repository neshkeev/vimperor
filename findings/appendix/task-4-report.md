# Task 4 Report: Run IdeaVim's regex parser tests on the JVM

## Status: DONE

## Exact pass/fail counts

Command:

```
./gradlew -p spike/antlr-kotlin jvmTest --tests '*VimRegexParserTest*' --console=plain
```

Result:

```
VimRegexParserTest[jvm] > test wider unicode character()[jvm] FAILED
    org.opentest4j.AssertionFailedError at VimRegexParserTest.kt:388

64 tests completed, 1 failed

BUILD FAILED in 1s
```

**64 tests total, 63 passed, 1 failed.** JUnit XML report at
`spike/antlr-kotlin/build/test-results/jvmTest/TEST-com.maddyhome.idea.vim.regexp.internal.VimRegexParserTest.xml`.

## The one failure

`test wider unicode character()` — pattern `assertSuccess("🨤")` (a Kotlin
surrogate-pair literal for U+1FA24 🨤, a supplementary-plane/astral character).

```
org.opentest4j.AssertionFailedError: Expecting successful parsing for pattern 🨤 but got E383
	at ...VimRegexParserTest.assertSuccess(VimRegexParserTest.kt:388)
	at ...VimRegexParserTest.test wider unicode character(VimRegexParserTest.kt:292)
```

`E383` originates from `BailErrorLexer.recover(LexerNoViableAltException)` — the generated
`RegexLexer` cannot lex the input at all; it never even reaches the parser.

## Triage table

**Fix round 1 update:** the team lead ruled that the original three-bin taxonomy (grammar /
error-strategy / spike artifact) doesn't cover this failure, and added a fourth bin,
**runtime/library divergence**, for exactly this case. The table below reflects that
ruling. See "Fix round 1" at the bottom of this report for the full reasoning and what
changed.

| Test | Classification | Evidence |
|---|---|---|
| `test wider unicode character` | **Runtime/library divergence** (counts against the gate) | See root-cause investigation below: isolated to a bug in antlr-kotlin's own `StringCharStream.codePoint()` (part of the `CharStreams.fromString` runtime, not IdeaVim's grammar or Task 3's port), which fails to decode a surrogate pair into its codepoint specifically when that codepoint is the *last* codepoint in the input stream, returning EOF (`-1`) instead. This causes `RegexLexer`'s wildcard-matching rules (`LITERAL_CHAR_MAGIC: '\\'? . -> type(LITERAL_CHAR);`, `RegexLexer.g4:177`) to see EOF instead of the character, so no lexer alternative matches → `LexerNoViableAltException` → `E383`. The defect reproduces through the bare `CharStreams.fromString`/`LA()` API with no grammar and no generated ATN involved — it is not specific to `RegexLexer`'s grammar and would misbehave identically for any antlr-kotlin grammar whose input ends in an astral-plane character. It is also not error-strategy divergence: the Java pipeline *succeeds* on this input (per the un-ported `vim-engine` test's own expectation) rather than reaching a failure path that the two pipelines then handle differently — the Kotlin pipeline fails a layer earlier, at input decoding, and never reaches the parser at all. |

**Summary counts (four bins):** grammar divergence: 0, error-strategy divergence: 0,
**runtime/library divergence: 1**, spike artifact: 0, unclassified: 0.

**All other 63 tests passed**, including every other Unicode-adjacent test (`test unicode character` — a single-UTF-16-unit BMP char, `test unicode character in nomagic mode`, `test unicode code range` — `[\u0-￿]`, `test collection russian alphabet` — Cyrillic BMP chars), every range/multi test, every collection test, every group/backreference test, and every explicit `assertFailure` (error-strategy) test. That means **63 of 63 grammar and error-strategy assertions passed clean** — the one failure is isolated entirely to the new runtime/library bin and should not be read as evidence against grammar or error-strategy fidelity.

### Root-cause investigation (why I'm confident this is a runtime/library divergence, not a spike artifact)

I did **not** modify the failure — investigation only, using a temporary scratch test file
(`spike/antlr-kotlin/src/commonTest/kotlin/scratch/DebugAstralTest.kt`) that I deleted
before committing; it is not part of the diff.

1. Decompiled `antlr-kotlin-runtime-jvm-1.0.13.jar`'s `StringCharStream` (backing
   `CharStreams.fromString`, called directly by Task 3's `VimRegexParser.kt:34`). It does
   attempt proper UTF-16-surrogate-pair-to-codepoint decoding (via a `codePointIndices`
   index and a `Character.isHighSurrogate` check combining high+low surrogate), so the
   CharStream layer is *designed* to be codepoint-aware, comparable to the Java runtime's
   `CodePointCharStream`.
2. Empirically probed `CharStreams.fromString(...).LA(1)` with three inputs:
   - `"🨤"` alone (the astral char is the *only*, hence *last*, codepoint): `size()=1`,
     `LA(1)=-1` (EOF!) — **bug reproduced**.
   - `"🨤x"` (astral char followed by another char, so *not* last): `size()=2`,
     `LA(1)=129572` (=0x1FA24, correct), `LA(2)=120` ('x') — decodes correctly.
   - `"x🨤"` then `consume()` once (astral char is last again, just reached via a different
     path): `size()=2`, `LA(1)=-1` after consuming past 'x' — **bug reproduced** again.

   This isolates the defect precisely: `StringCharStream.codePoint()` fails whenever the
   surrogate pair being decoded is the **last** codepoint in the stream, independent of
   what precedes it, and independent of grammar/lexer code entirely — it reproduces with
   the bare CharStream API, no `RegexLexer` involved.
3. Cross-checked against Task 3's report: Task 3 never touched `CharStreams`, never wrote
   any lexer/CharStream code, and the `VimRegexParser.kt:34` call
   (`CharStreams.fromString(...)`) is a one-line call into antlr-kotlin's own library code
   with no wrapping or stubbing. There is no spike-introduced approximation anywhere in this
   path — this is exactly what a real port would do.
4. `RegexLexer.g4:177`'s `LITERAL_CHAR_MAGIC: '\\'? . -> type(LITERAL_CHAR);` is the only
   grammar rule that would need to match a bare wide char with no other lexer mode active,
   confirming the mechanism (wildcard `.` needs `LA(1)` to return the real codepoint; it
   gets EOF instead).

This is a genuine bug in antlr-kotlin's `com.strumenta.antlrkotlin.runtime`/
`org.antlr.v4.kotlinruntime.StringCharStream` (version 1.0.13), not something introduced by
this spike's stubbing, and not fixable by touching IdeaVim's ported code — the fix would
have to land upstream in antlr-kotlin itself. Per the brief's central instruction, **I did
not fix it.**

## Changes to the copied test file

Exactly one line, the import swap specified by the brief's Step 2:

```diff
-import org.junit.jupiter.api.Test
+import kotlin.test.Test
```

`kotlin.test.fail` was already imported in the original file; no other line was touched
(confirmed via `diff -u` against the `vim-engine` original — see Self-Review below).

## JUnit5-construct finding

Searched the file for `@Nested`, `@ParameterizedTest`, `@BeforeEach`, `@TestFactory`,
`@Disabled`: **none appear.** The only JUnit5-only construct in this file is the plain
`@Test` annotation, which the Step 2 import swap already handles. This is a positive
signal for the Phase 2/Task 7 migration estimate: this particular test class needed no
structural changes, only the one-line import swap.

## Files changed

```
A  spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/VimRegexParserTest.kt
```

`vim-engine/`, `src/`, and the root `settings.gradle.kts` are untouched
(`git diff --stat -- vim-engine src` is empty).

Committed at `93d2762f9`. This report file itself is not part of that commit — like prior
tasks' reports, `.superpowers/sdd/2026-08-16-antlr-kotlin-gate/` is covered by
`.superpowers/sdd/.gitignore`, so it is intentionally left untracked, matching Task 3's
precedent.

## Self-review findings

- `git status --short` before commit shows only the one new file under
  `spike/antlr-kotlin/src/commonTest/.../VimRegexParserTest.kt`, plus the pre-existing
  untracked `.omc/` (not created by this task, present since session start).
- `git diff --stat -- vim-engine src` is empty.
- `diff -u` of the copied file against the `vim-engine` original shows exactly the one-line
  import swap and nothing else — no reformatting, no renamed identifiers, no logic changes.
  The MIT copyright header (`Copyright 2003-2023 The IdeaVim authors`) is preserved
  verbatim.
- The temporary `scratch/DebugAstralTest.kt` file used for root-cause investigation was
  deleted before staging/committing; `git status --short` after cleanup shows it is gone
  and does not appear in the commit diff.
- Re-ran `./gradlew -p spike/antlr-kotlin jvmTest --tests '*VimRegexParserTest*'
  --console=plain` after cleanup to confirm the reported 64/63/1 counts are reproducible
  and unaffected by the investigation: same result, same single failure.
- I did not modify `VimRegexParser.kt`, `BailErrorLexer.kt`, `VimRegexParserErrorStrategy.kt`,
  or any other Task 3 file. The one failure is left as a genuine, documented divergence.

## Concerns for Task 5 (running the same suite on Node/JS)

1. **The astral-character failure may behave differently on Kotlin/JS**, and Task 5 should
   check it explicitly rather than assume the same root cause applies. JS strings are also
   UTF-16 code-unit sequences, so `StringCharStream`'s surrogate-pair-decoding logic is
   *shared* commonMain/expect-actual code in principle — but I only decompiled and probed
   the **JVM** artifact (`antlr-kotlin-runtime-jvm-1.0.13.jar`); I have not verified the JS
   runtime artifact has the identical bug. If Task 5 sees the same `test wider unicode
   character` failure with the same `E383`/EOF signature, that corroborates this being a
   shared-code bug rather than a JVM-specific one worth a separate line item. If Task 5
   sees it pass, or fail differently, that's a new, real JVM-vs-JS asymmetry worth its own
   report entry — don't assume they must match.
2. **This is the only failure in the entire suite** — the other 63 tests, including all
   other Unicode-adjacent cases (BMP chars, `[\u0-￿]` collections, Cyrillic), passed
   cleanly. This is strong positive signal that antlr-kotlin's grammar/parser fidelity is
   good for the vast majority of IdeaVim's regex syntax; the one failure is narrowly scoped
   to "the input's very last character is outside the Basic Multilingual Plane," which is a
   corner case but a real one (e.g. matching emoji or other astral-plane text at the end of
   a search pattern).
3. Per the brief's original binary bucket definitions, I initially classified this as
   **grammar divergence** rather than inventing a fourth "runtime library bug" bucket,
   since from this gate's perspective ("does antlr-kotlin's output behave like the Java
   ANTLR output on identical input") the observable effect is superficially identical to a
   grammar divergence: the same pattern is accepted by one pipeline and rejected by the
   other. I flagged this tension explicitly at the time, since my own evidence (the failure
   reproduces through the bare `CharStream` API with no grammar or ATN involved) argued
   against "grammar divergence" in the brief's stricter sense. **Fix round 1 resolved this**:
   the team lead added a fourth bin, runtime/library divergence, and this test is now
   classified there instead — see "Fix round 1" below.

## Fix round 1 (report-only, no code changes)

Independent review verified the implementation, the test-copy fidelity (one import line
diff, confirmed), and the root-cause work: the reviewer independently compiled a probe
against `antlr-kotlin-runtime-jvm-1.0.13.jar` and reproduced the `CharStream.LA()` findings
above, with numbers matching mine exactly. It also confirmed the file has 64 `@Test`
annotations, matching the reported 64 tests. **The measurement itself was approved
unchanged.**

The one finding was in this report's taxonomy, not in the measurement or the code:

1. **The team lead ruled the original three-bin taxonomy (grammar / error-strategy / spike
   artifact) did not cover this failure**, and that I was right to flag the tension in the
   original Concern #3 rather than force-fitting it. Reasoning for the new bin:
   - Not **grammar divergence** ("antlr-kotlin generates a parser that accepts/rejects
     differently") — the fault reproduces through the bare `CharStreams.fromString`/`LA()`
     API with no grammar and no generated ATN involved. It would misbehave identically for
     any antlr-kotlin grammar whose input ends in an astral-plane character, not just
     `RegexLexer`'s.
   - Not **error-strategy divergence** — that bin presupposes both pipelines reach a
     failure path and diverge in handling it. Here the Java pipeline *succeeds* on this
     input; the Kotlin one fails a layer earlier, at input decoding, and never reaches the
     parser.
   - Correctly not a **spike artifact** — Task 3 wrote no CharStream or lexer code; this is
     upstream antlr-kotlin library code, untouched by any spike stubbing.
   - **New bin: runtime/library divergence** — a defect in antlr-kotlin's shared runtime
     infrastructure, independent of any particular grammar.
2. **Reclassified `test wider unicode character` from "grammar divergence" to "runtime/library
   divergence"** in the triage table itself (not just prose), so a reader skimming only the
   table gets the accurate signal.
3. **Restated summary counts under the four bins**: grammar 0, error-strategy 0,
   runtime/library 1, spike artifact 0, unclassified 0. Previously stated as "grammar
   divergence: 1" under the old three-bin scheme.
4. **Why this matters for the rollup**: the bin drives Phase 1's conclusion. A
   grammar-generation defect would mean the toolchain cannot reliably reproduce IdeaVim's
   grammar semantics — serious and structural. A CharStream bug in one runtime version is
   narrow, reproducible under a single stated condition (astral char at end of input), and
   plausibly upstream-patchable or locally workaroundable without touching the grammar at
   all. Left as "Grammar divergence: 1," the finding over-weighted as evidence against
   grammar fidelity, when in fact 63 of 63 grammar and error-strategy assertions passed
   clean.
5. Original Concern #3 is preserved above (not deleted) since it anticipated this exact
   distinction from the evidence at the time, and it's worth keeping visible that the tension
   was flagged rather than missed.

**No code changed in this fix round, so no tests were re-run.** The build state from the
original report stands: `./gradlew -p spike/antlr-kotlin jvmTest --tests
'*VimRegexParserTest*'` → 64 tests, 63 passed, 1 failed (verified at commit `93d2762f9`,
unchanged by this fix round).

## Report file

`/Users/neshkeev/projects/ideavim/.superpowers/sdd/2026-08-16-antlr-kotlin-gate/task-4-report.md`
