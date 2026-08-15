# Task 7 Report: Sizing the JUnit5 to kotlin.test migration

Measurement only. No production, spike, or test code was modified.

**Fix round 1 (applied after initial review):** corrected the
`productForArguments` shared-helper usage count from 9/21 files to the
correct **10/21 (48%)** — `LambdaTests.kt` was omitted from the original
prose list by transcription error, even though the raw `grep` result already
included it. Also added context from the team lead: the 594-file
`VimTestCase`/IntelliJ-fixture dependency flagged below is a known, planned
piece of phase 2 (a headless in-memory host), not an unplanned blocker — see
the marked notes inline for both corrections. Nothing was re-run; no code or
counts other than these two write-up errors changed.

## Step 1: Raw counts

Commands run (exact, from the brief) against `src/test`:

```bash
echo "files in src/test:            $(find src/test -name '*.kt' | wc -l)"
echo "extending VimTestCase:        $(grep -rl 'VimTestCase' src/test --include='*.kt' | wc -l)"
for c in '@Nested' '@ParameterizedTest' '@BeforeEach' '@AfterEach' '@TestFactory' \
         '@RepeatedTest' '@Disabled' '@ValueSource' '@MethodSource' '@CsvSource' '@TestTemplate'; do
  printf '%-22s %s\n' "$c" "$(grep -rl -- "$c" src/test --include='*.kt' | wc -l)"
done
```

Note: the repo's `grep` shell alias wraps `ugrep`, which mis-warns on an
unquoted `--include=*.kt` glob (it gets glob-expanded by the shell before
`ugrep` sees it). Quoting `--include='*.kt'` removes the warning; the counts
were verified identical both ways and cross-checked against the real
`/usr/bin/grep` (BSD grep 2.6.0) with the quoted flag. All numbers below are
trustworthy.

```
files in src/test:            653
extending VimTestCase:        594
@Nested                       0
@ParameterizedTest            21
@BeforeEach                   174
@AfterEach                    32
@TestFactory                  0
@RepeatedTest                 0
@Disabled                     37
@ValueSource                  1
@MethodSource                 20
@CsvSource                    0
@TestTemplate                 0
```

## Step 2: Which constructs have no kotlin.test equivalent, and the union count

`kotlin.test` provides `@Test`, `@BeforeTest`, `@AfterTest`, `@Ignore`. That
covers `@BeforeEach` → `@BeforeTest`, `@AfterEach` → `@AfterTest`, and
`@Disabled` → `@Ignore` cleanly — those three constructs (174 + 32 + 37 file
occurrences) are **not** part of the quarantine floor.

No equivalent exists for: `@Nested`, `@ParameterizedTest`, `@TestFactory`,
`@RepeatedTest`, `@ValueSource`, `@MethodSource`, `@CsvSource`,
`@TestTemplate`.

Per-construct file counts for that unsupported set:

| Construct | Files |
|---|---|
| `@Nested` | 0 |
| `@ParameterizedTest` | 21 |
| `@TestFactory` | 0 |
| `@RepeatedTest` | 0 |
| `@ValueSource` | 1 |
| `@MethodSource` | 20 |
| `@CsvSource` | 0 |
| `@TestTemplate` | 0 |

Sum of per-construct counts: 42 (double-counts files using both
`@ParameterizedTest`+`@MethodSource`, which is the normal pairing).

**Deduplicated union** (computed with `grep -rlE` over the alternation of all
eight patterns, then verified by `comm` against the `@ParameterizedTest`-only
file list):

```
UNION = 21 files
```

The union equals the `@ParameterizedTest` count exactly — every file using
`@ValueSource` or `@MethodSource` also uses `@ParameterizedTest` on the same
file (expected: those provider annotations only mean anything paired with
`@ParameterizedTest`), and no file uses `@Nested`/`@TestFactory`/
`@RepeatedTest`/`@CsvSource`/`@TestTemplate` at all. Sanity check: union (21)
≤ sum of per-construct counts (42) ✓, and the per-construct tally never
exceeds the 653-file total ✓ — these are file counts, not occurrence counts.

**Quarantine floor for phase 2: 21 of 653 files (3.2%)** — small and
concentrated, all attributable to `@ParameterizedTest`.

## Step 3: Three sampled files, effort bands

All three are drawn from the 21-file `@ParameterizedTest` set (there were no
`@Nested` files to sample, so all three necessarily come from the
`@ParameterizedTest` bucket per the brief's instruction to pick from either).

### 1. `src/test/java/org/jetbrains/plugins/ideavim/action/change/insert/InsertExitModeActionTest.kt`

One `@ParameterizedTest` with `@ValueSource(strings = ["i", "a", "o", "O"])`,
no companion object, no shared helper:

```kotlin
@ParameterizedTest
@ValueSource(strings = ["i", "a", "o", "O"])
fun `test read-only file allows insert entry but blocks changes`(insertCommand: String) {
  configureByText("12${c}3")
  ...
}
```

**Effort band: mechanical, trivial.** Replace with a single `@Test` wrapping
a `for (insertCommand in listOf("i", "a", "o", "O")) { ... }` loop. No
companion object, no external data, one call site. Minutes of work.

### 2. `src/test/java/org/jetbrains/plugins/ideavim/action/change/delete/DeleteMotionActionTest.kt`

One `@ParameterizedTest` + `@MethodSource("repeatFindAndTillTestCase")`,
backed by a `companion object { @JvmStatic fun ...(): Stream<Arguments> }`
returning 8 tuples of `(content, keys, expected)`:

```kotlin
companion object {
  @JvmStatic
  fun repeatFindAndTillTestCase(): Stream<Arguments> {
    return Stream.of(
      arguments("${c}111b222b333", "dtbd;", "${c}b333"),
      ...
    )
  }
}

@ParameterizedTest
@MethodSource("repeatFindAndTillTestCase")
fun `test delete repeat find and till motion`(content: String, keys: String, expected: String) {
  typeTextInFile(keys, content)
  assertState(expected)
}
```

**Effort band: mechanical.** Replace `Stream<Arguments>` with a
`List<Triple<String, String, String>>` (or a small local data class) and loop
in a single `@Test`. `java.util.stream.Stream` and
`org.junit.jupiter.params.provider.Arguments` imports drop out entirely.
Slightly more than sample 1 (touches a companion object and a stream import)
but still a same-shape, no-judgment-calls rewrite.

### 3. `src/test/java/org/jetbrains/plugins/ideavim/ex/parser/commands/CommandTests.kt`

Three `@ParameterizedTest` methods, all backed by a **shared** helper:

```kotlin
companion object {
  @JvmStatic
  val values = listOf("", " ")
  @JvmStatic
  fun arg1(): List<Arguments> = productForArguments(values)
  @JvmStatic
  fun arg3(): List<Arguments> = productForArguments(values, values, values)
}

@ParameterizedTest
@MethodSource("arg1")
fun `command with marks in range`(sp: String) { ... }
```

`productForArguments` is **not local** — it's defined once in
`src/testFixtures/kotlin/org/jetbrains/plugins/ideavim/util.kt:95` as
`fun productForArguments(vararg elements: List<String>): List<Arguments>`,
and is reused by **10 of the 21** affected files (`CommandTests`,
`ForLoopTests`, `WhileLoopTests`, `TryCatchTests`, `IfStatementTests`,
`FunctionDeclarationTests`, `SublistExpressionTests`,
`TernaryExpressionTests`, `LambdaTests`, `ConcatenationOperatorTest`) to
build the cartesian product of whitespace-variant test inputs.

**Effort band: mechanical, but with a shared-dependency wrinkle.** The
per-call-site rewrite (loop over the product instead of
`@ParameterizedTest`/`@MethodSource`) is exactly as mechanical as samples 1–2
for each of the 10 files. But `productForArguments`'s return type
(`List<Arguments>`, a JUnit5 type) has to change once, centrally, before any
of those 10 files can compile against kotlin.test. That's a single one-time
fix (change the return type to e.g. `List<List<String>>` and update the 10
call sites' unpacking) — net effect: fixing one shared helper unblocks 48%
of the 21-file union at once. Not structural, but not purely local either;
worth sequencing first in phase 2 since it has the highest leverage.

> **Correction (fix round 1):** the original version of this report said "9
> of the 21" and omitted `LambdaTests.kt` from the named list. That was a
> transcription error — the `grep -rl productForArguments` output I actually
> collected while researching this file already included `LambdaTests.kt`
> (it calls `productForArguments` three times, for `arg3`/`arg4`/`arg6` at
> lines 31/34/37, importing it at line 18), but it was dropped when I wrote
> the prose list. Corrected to 10/21 (48%) throughout this report. Nothing
> was re-run to produce this correction — the original raw grep result
> already had the right file; only the write-up was wrong.

## Unanticipated finding

**The JUnit5 annotation count is not the dominant constraint.** 594 of 653
files (91%) extend `VimTestCase`
(`src/testFixtures/kotlin/org/jetbrains/plugins/ideavim/VimTestCase.kt`),
which is built directly on IntelliJ Platform test infrastructure —
`com.intellij.testFramework.fixtures.CodeInsightTestFixture`,
`LightProjectDescriptor`, `IdeaTestFixtureFactory`, `PlatformTestUtil`, and
similar JVM/IDE-only APIs with no JS/multiplatform equivalent. That
dependency is orthogonal to JUnit5 vs. kotlin.test and is not something a
test-framework swap fixes: files extending `VimTestCase` cannot run in a
`commonTest`/JS target regardless of what testing annotations they use,
because the fixture machinery itself doesn't exist outside the IDE process.

Cross-checking against the 21-file union: 19 of the 21 extend `VimTestCase`
(only `LambdaTests.kt` and `TernaryExpressionTests.kt` don't). So even after
a kotlin.test rewrite, most of these 21 files — and the other ~570 files
that pass Step 1/2 cleanly — would still be JVM-locked by their base class,
not by their test annotations.

I did not check whether any of the 594 `VimTestCase` subclasses could be
refactored to a lighter, fixture-free base (some, like the parser tests in
sample 3, only call `VimscriptParser.parseCommand` and may not need IDE
fixtures at all) — that's outside this task's scope, but it's the number
that actually gates how much of the suite can reach `commonTest`, and it's
worth someone sizing explicitly before phase 2 commits to a file count.

> **Added context (fix round 1), from outside this task's brief:** the
> project's design spec already plans for exactly this. Phase 2's core work
> is building a headless in-memory host and re-pointing `VimTestCase`'s
> ~16-method surface at it, replacing the IntelliJ Platform fixture
> dependency rather than porting it file-by-file. So the framing above should
> not be read as "phase 2 is blocked" or as an unplanned obstacle — it's a
> known, planned piece of the work. Given that, the accurate sizing picture
> is: the JUnit5-annotation gap measured in this report (21 files, all
> sampled as mechanical) is a **small** component of phase 2's cost; the
> headless host replacing `VimTestCase`'s fixture dependency is the **main
> body** of the work. My recommendation to size the fixture dependency
> explicitly still stands as useful groundwork for that host-building effort
> — it just sits inside an existing plan rather than revealing a new one.

## Self-review

- `git status --short` before and after this task shows only
  `docs/superpowers/plans/2026-08-16-antlr-kotlin-gate.md` (modified) and
  `.omc/` (untracked) — both pre-existing before this task started (the
  `.omc/` untracked path was already listed in the initial session git
  status; the plan doc diff is progress tracking from the surrounding
  orchestration, not touched by this task). I created no files under `src/`,
  `vim-engine/`, or `spike/antlr-kotlin/`, and edited nothing.
- Per-construct counts (max 174, for `@BeforeEach`) never exceed the 653-file
  total — confirms these are file counts (`grep -l`), not occurrence counts.
- Union count (21) is ≤ sum of unsupported-construct per-file counts (42) ✓.
- Union count (21) was independently verified two ways: (a) `grep -rlE` over
  the 8-pattern alternation, (b) `comm -23` between the union file list and
  the `@ParameterizedTest`-only file list, which came back empty — confirming
  no file uses `@ValueSource`/`@MethodSource`/etc. without also using
  `@ParameterizedTest`.

## Concerns

1. **The 21-file quarantine floor is only the small part of phase 2's cost.**
   It counts JUnit5-annotation incompatibility, which — per the team lead —
   is a known, small slice of the work: the much larger, already-planned
   piece is replacing `VimTestCase`'s dependency on IntelliJ Platform test
   fixtures (594 of 653 files, 91%) with a headless in-memory host, per the
   project's design spec (see "Unanticipated finding" above; this is not an
   unplanned blocker, just a larger cost center than the annotation gap
   alone). If phase 2 status reporting ever leads with "21 files need work"
   without noting that qualifier, it will understate the real scope. My
   original recommendation stands as useful groundwork either way: size how
   many `VimTestCase` subclasses are IDE-fixture-light in practice (like the
   parser tests in sample 3) versus genuinely need editor/action fixtures, to
   help scope the headless-host effort.
2. **`productForArguments` (src/testFixtures/.../util.kt:95) is a
   single point of leverage/risk** for 10 of the 21 files (corrected in fix
   round 1 — see the note under sample 3; originally reported as 9).
   Fixing it first in phase 2 execution order would unblock 48% of the union
   in one change; conversely, if it's missed, 10 files would fail to compile
   together rather than independently.
3. Sample size is 3 of 21 (the brief explicitly asks for a per-file band, not
   an extrapolated total, and I have not extrapolated). All three sampled
   files turned out mechanical — that should not be read as "all 21 are
   mechanical." `@Nested` had zero hits so no structural sample was
   available to draw from; if any of the 18 unsampled `@ParameterizedTest`
   files nest test classes or mix parameterization with more complex fixture
   setup, that could look different. I did not check the other 18.
