# Phase 0 finding: antlr-kotlin viability

**Date:** 2026-08-16
**Question:** Can antlr-kotlin replace IdeaVim's Java ANTLR parsers on `jvm` and `js`?
**Recommendation:** **GO WITH CAVEATS** — 7 caveats, enumerated in section 6, to be folded into phase 1.

**Spec this serves:** `docs/superpowers/specs/2026-08-16-vim-engine-multiplatform-design.md`
(sub-project 1, workstream W1, phase 0 gate).

This gate existed because `vim-engine` does not merely consume ANTLR-generated Java parsers —
it subclasses the ANTLR *runtime* (`BailErrorLexer extends Lexer`,
`VimRegexParserErrorStrategy extends DefaultErrorStrategy`) and uses `CharStreams`,
`CommonTokenStream`, `ParserRuleContext`, `ParseTree`, `Token`, `Recognizer`,
`BaseErrorListener`, `RecognitionException`, and `LexerNoViableAltException` directly
(spec §5, W1). If antlr-kotlin could not carry the grammars *and* that runtime surface to
Kotlin on both targets, the whole Multiplatform-to-JS approach was in question and the
bundled-JVM sidecar (spec §1) would have to be reopened.

It can. The parser layer is not where this port will fail. But four costs surfaced that the
spec did not anticipate, and one antlr-kotlin runtime bug is real and unfixed. Both are
detailed below.

---

## 1. Evidence

Every figure below is traceable to one of the seven per-task reports, preserved verbatim in
`findings/appendix/`. Two rows are marked as this report's own checks, not inherited numbers.

| Check | Result | Source |
|---|---|---|
| Regex grammars generate | **Yes** — after one token-label rename and one hand-translated Java `@members` block | Task 2 |
| Vimscript grammar generates | **Yes** — byte-identical copy of `Vimscript.g4`, no edits at all | Task 6 |
| Parser subpackage compiles (jvm) | **Yes** | Task 3 |
| Parser subpackage compiles (js) | **Yes** | Task 3 |
| `VimRegexParserTest` on JVM | **63 of 64 tests pass**, 1 fails | Task 4 |
| `VimRegexParserTest` on Node | **63 of 64 tests pass**, 1 fails — *identical* failure, byte-for-byte identical assertion message | Task 5 |
| Vimscript corpus divergences | **0 of 1,865** (0.0%) | Task 6 |
| JS:JVM parse time ratio | **≈1×** (1.23× and 0.91× on two cold runs) — directional only, see §5 | Task 5 |
| JUnit5-only test files | **21 of 653** (3.2%), all sampled cases mechanical | Task 7 |
| Vimscript parser compiles for JS | **Yes** — *this report's own check, see below* | §1.2 |
| Generator emits `@Suppress("UNSAFE_CALL")` | **4 sites**, `VimscriptParser.kt` only — *this report's own check* | §1.2 |

**Note on the test count.** The plan's report template anticipated "N/397 assertions" for
`VimRegexParserTest`. The file actually contains 64 `@Test` methods (independently confirmed
by counting `@Test` annotations during Task 4's review). 397 is the test file's **line count** —
the plan states "`VimRegexParserTest` (397 lines)" at `:39` and again at `:473`, and `wc -l`
returns exactly 397; the report template reused it as an assertion denominator. Nothing was lost
or skipped. 64/63/1 is the real, reproducible number on both targets.

**Note on `VimTestCase` counts.** The spec's baseline table (§3) records 598 `VimTestCase`
subclasses; Task 7 measured 594 today, against 653 total test files. The spec itself says to
re-measure if picked up later. The 4-file delta does not change any conclusion here, but phase
2 should use freshly measured numbers, not the spec's.

### 1.1 Toolchain

Standalone Gradle build at `spike/antlr-kotlin/`, invisible to the root build. Kotlin
Multiplatform 2.3.20, `jvmToolchain(21)`, targets `jvm()` and `js { nodejs() }`,
`com.strumenta.antlr-kotlin` **1.0.13**. Task 1 proved both test runners genuinely execute (a
deliberately failing assertion surfaced independently under JUnit 5 on the JVM and under
Node/Mocha via kotlin.test's JS shim) before any ANTLR was introduced — so "the JS tests
passed" in later tasks is not "the JS tests silently did nothing". Node and Yarn
auto-provisioned without intervention.

### 1.2 Two checks this report ran itself

The per-task reports left one gap. Task 6 ran the Vimscript differential in the `jvmTest`
source set only, and it added `Vimscript.g4` to the spike *after* Task 5's JS runs. So no
per-task report establishes that the 16,183-line generated `VimscriptParser.kt` compiles for
Kotlin/JS at all — the largest generated artifact in the spike had JVM evidence only.

I ran it:

```
$ ./gradlew -p spike/antlr-kotlin compileKotlinJs compileKotlinJvm --console=plain
BUILD SUCCESSFUL in 7s
```

The generated Vimscript lexer, parser, visitors and listeners compile for both targets. The
Vimscript grammar's *parse behavior* is still JVM-only evidence (see §4) — this closes the
compilation gap, not the behavioral one.

That run surfaced a second, minor finding not in any per-task report: the antlr-kotlin
generator emits `@Suppress("UNSAFE_CALL")` on four semantic-predicate functions in
`VimscriptParser.kt` (`commandName_sempred`, `expr_sempred`, and two others; lines 16135,
16144, 16165, 16175 — that file is regenerated build output under `build/generatedAntlr/`, so
the line numbers are reproducible from the grammar but not from a committed file).
`UNSAFE_CALL` is a compiler *error*, not a warning, and Kotlin responds with:

> Suppression of error 'UNSAFE_CALL' might compile and work, but the compiler behavior is
> UNSPECIFIED and WILL NOT BE PRESERVED.

Zero such suppressions appear in the regex grammars' output — this is specific to Vimscript's
left-recursive rules. It compiles today on Kotlin 2.3.20 and nothing observed depends on it,
but it means the generated Vimscript parser rests on behavior the Kotlin team has explicitly
declined to guarantee across compiler versions. Low severity, but it belongs on the phase 1
list because it is a Kotlin-upgrade risk that no amount of testing today will catch.

---

## 2. Porting friction

Two grammars and 672 lines of engine source were ported. Everything that had to change,
grouped by where it bit.

### 2.1 Grammar-level (Task 2)

**a. `RegexLexer.g4` embeds Java.** It carries an `@members` block (`public Boolean
ignoreCase = null;` plus two setters) called from **8 inline lexer actions** across all four
lexer modes. ANTLR copies action bodies verbatim into the target language, so this had to be
hand-translated to Kotlin: `var ignoreCase: Boolean? = null`, and the two setters renamed
`setIgnoreCase`/`setNoIgnoreCase` → `markIgnoreCase`/`markNoIgnoreCase` to avoid colliding
with the accessor Kotlin synthesizes for the `ignoreCase` property. The property name itself
was preserved.

**b. A token label collided with a `ParserRuleContext` member.** `RegexParser.g4:158` used
`start=` as a label in the `RangeColElem` alternative of `collection_elem`. ANTLR generates a
field per token label on the rule's context class; `org.antlr.v4.kotlinruntime.ParserRuleContext`
already declares `start`. Java permits field hiding silently — which is exactly what the
current `vim-engine` build does today, unnoticed. Kotlin requires `override`, and `override`
is *unavailable* here because `getStart`/`setStart` are `final` in the Kotlin runtime's
bytecode (confirmed via `javap`). Hard compile error on both targets:

```
e: RegexParser.kt:3728:48 'start' hides member of supertype 'ParserRuleContext'
   and needs an 'override' modifier.
```

Renaming the label to `rangeStart=` fixed it and the build went straight to green with no
further errors. The rename is semantically inert at the grammar level, but it is **not
spike-only cosmetics** — it must land in the real `vim-engine/src/main/antlr/RegexParser.g4`,
and it ripples into engine source at
`vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp/parser/visitors/CollectionElementVisitor.kt:27`,
where `ctx.start.text` must become `ctx.rangeStart.text`.

The full reserved-member list, from `javap` on the Kotlin runtime's `ParserRuleContext`:

```
children, start, stop, exception, payload, parent
```

Any token label in any of the three grammars reusing one of these six names hits the same
unresolvable wall. A spot grep of `Vimscript.g4` (Task 2) and a fuller check during Task 6
both found zero matches there, and `start=` was the only hit in the regex grammars — but this
needs to be a standing static check on grammars destined for Kotlin, not a one-time fix.

**Consequence of (a) and (b) together: the `.g4` files cannot be shared byte-for-byte between
the Java build and the Kotlin build.** `Vimscript.g4` can (it needed no edits whatsoever);
`RegexLexer.g4` and `RegexParser.g4` cannot. Phase 1 needs an explicit decision — see caveat 1.

### 2.2 Runtime-API differences in ported engine code (Task 3)

Task 3 copied `vim-engine`'s `regexp/parser/` subpackage into the spike, rewrote
`org.antlr.v4.runtime` → `org.antlr.v4.kotlinruntime`, and compiled until green. **35 sites
across 4 distinct root causes** were genuine antlr-kotlin API differences, in 672 lines of
ported code:

| # | Root cause | Sites | Files |
|---|---|---|---|
| 1 | `Recognizer.errorListeners` is a plain `List` with no `.clear()`; use `removeErrorListeners()` | 1 | `VimRegexParser.kt` |
| 2 | **Java platform-type nullness vs. Kotlin's exact-match override rule** | **28** | `VimRegexParserErrorStrategy.kt`, `CollectionElementVisitor.kt`, `MultiVisitor.kt` |
| 3 | `AbstractParseTreeVisitor<T>.defaultResult()` is `abstract` in the Kotlin runtime; it has a body (`return null`) in Java | 2 | `CollectionElementVisitor.kt`, `MultiVisitor.kt` |
| 4 | `Token?` context-label fields and `Token.text: String?` are nullable in the Kotlin runtime | 4 | `CollectionElementVisitor.kt`, `MultiVisitor.kt` |

**Correction to the appendix.** `appendix/task-3-report.md:176` reports "`CollectionElementVisitor.kt`
(11 methods)" for root cause 2. The file has **19** nullable-`ctx` overrides
(`grep -c 'ctx: RegexParser\.[A-Za-z0-9_]*Context?'` returns 19 in `vim-engine`, 0 in the spike
copy). That moves root cause 2 from 20 to 28 and the total from 27 to 35. The counts above are
the corrected ones; the appendix is preserved verbatim as the original record and still carries
the undercount. Root causes 1, 3 and 4 were re-verified exact. The undercount fell entirely on
root cause 2 — the one this section identifies as the cost driver — so it erred in the direction
of making the port look cheaper.

**Root cause 2 is the one that matters for costing**, because it is the only one that scales
with something other than lines of code. `vim-engine` was written against the Java runtime,
where every reference type arrives in Kotlin as an unenforced *platform type* — so declaring
an override parameter nullable (`ctx: XyzContext?`, `recognizer: Parser?`) was free and
carried no compile-time consequence. The pure-Kotlin runtime declares those parameters
strictly non-null, and Kotlin's override rule permits no contravariant relaxation of
nullability. Every such signature is rejected.

In this task alone that was 3 sites on `DefaultErrorStrategy` (`recover`, `recoverInline`,
`sync`) plus 25 `visitXyz` overrides across two visitor classes (19 in
`CollectionElementVisitor.kt`, 6 in `MultiVisitor.kt`). **It recurs for every
`visitXyz` override on every ANTLR visitor in the real port** — `ExecutableVisitor`,
`ExpressionVisitor`, `ScriptVisitor`, `CommandVisitor`, `PatternVisitor`, and more. Task 3
estimates plausibly hundreds of signature edits across the full port. Each edit is trivial
(strip a `?`, recompile, repeat); the count is not.

One direction-of-travel warning from Task 3 worth repeating: Task 2 *predicted*
`DefaultErrorStrategy.recover` would need a nullable `Parser?`; it needed the opposite. The
Kotlin runtime is stricter than Java almost everywhere, but not uniformly, so "assume it's
Java plus `?` everywhere" is not a safe shortcut. Each signature needs checking.

### 2.3 A JVM-only stdlib call, unrelated to ANTLR (Task 3, item 8)

`CollectionElementVisitor.kt:107` calls `Char.isJavaIdentifierPart()`. That is a JVM-only
extension in Kotlin's standard library. `compileKotlinJvm` had already gone green;
`compileKotlinJs` failed on it alone.

It is not the only site. `vim-engine` has **three** today — `CollectionElementVisitor.kt:107`,
`PatternVisitor.kt:169`, and `PatternVisitor.kt:181` — but only the first was reachable, because
`PatternVisitor.kt` was pruned from the spike (§4). So the compiler surfaced one of three, and
the sweep in caveat 5 starts with two known hits already waiting in the file this gate never
compiled.

This has nothing to do with ANTLR. It is a **pre-existing JVM-only stdlib call in `vim-engine`**,
surfaced only because this is the first time any of that source has been compiled for a
non-JVM target — and it was found in the *first 672 lines* anyone compiled. `vim-engine` is
~79k lines (spec §3).

This is direct evidence about the spec's **W4 ("JDK substitutions")**, which §5 characterises
as `EnumSet → Set`, `Locale → explicit case handling`, `Pattern → kotlin.text.Regex` —
type-level swaps. At least one substitution changes **behavior**, not just types. The spike
approximated it as `isLetterOrDigit() || it == '_' || it == '$'`, which is *not* the JDK's
rule (currency symbols and Unicode identifier-ignorable characters differ), and which would
change `\i` / `[:ident:]` character-class matching for non-ASCII input. See §4 for why this
approximation did not contaminate any result, and caveat 5 for what it implies.

---

## 3. Divergences found

Four bins are used. The plan's original three (grammar / error-strategy / spike artifact) did
not cover the one real failure, and a fourth — **runtime/library divergence** — was added
mid-phase for it.

| Bin | Count | Detail |
|---|---|---|
| Grammar divergence | **0** | No case where antlr-kotlin's parser accepts or rejects differently from the Java parser. 63 of 63 assertions outside the runtime-divergence bin pass; 1,865 of 1,865 Vimscript parse trees are byte-identical. |
| Error-strategy divergence | **0** | Every explicit `assertFailure` test in `VimRegexParserTest` passes. The Vimscript corpus includes entries that trigger ANTLR's default error-recovery path (producing error nodes rather than throwing) and those trees matched too. This is the sub-risk the spec called out by name in W1 — "the error-strategy subclassing is exactly where a reimplementation is most likely to diverge" — and it did not diverge. |
| **Runtime/library divergence** | **1** | The astral-plane `CharStream` bug, below. |
| Spike artifact | **0** | No failure was traced to the spike's own stubbing. |
| Unclassified | **0** | |

### The one failure: `test wider unicode character`

Pattern: `assertSuccess("🨤")` — U+1FA24, a single supplementary-plane codepoint.
Result on both targets: `E383`, raised from `BailErrorLexer.recover(LexerNoViableAltException)`.
The lexer cannot tokenize the input at all; it never reaches the parser.

Root cause, established by decompiling `antlr-kotlin-runtime-jvm-1.0.13.jar` and probing the
bare `CharStreams.fromString(...).LA()` API with no grammar and no generated ATN involved:

| Input | `size()` | `LA(1)` | |
|---|---|---|---|
| `"🨤"` (astral char is last) | 1 | `-1` (EOF) | **bug** |
| `"🨤x"` (astral char not last) | 2 | `129572` = 0x1FA24 | correct |
| `"x🨤"`, after one `consume()` (astral char last again) | 2 | `-1` (EOF) | **bug** |

`StringCharStream.codePoint()` fails to decode a surrogate pair into its codepoint **when that
codepoint is the last one in the stream**, returning EOF instead. `RegexLexer.g4:177`'s
wildcard rule (`LITERAL_CHAR_MAGIC: '\\'? . -> type(LITERAL_CHAR);`) then sees EOF, no
alternative matches, and the bail lexer reports E383.

Classification reasoning:
- **Not grammar divergence** — it reproduces through the bare CharStream API with no grammar
  involved, and would misbehave identically for *any* antlr-kotlin grammar whose input ends in
  an astral-plane character.
- **Not error-strategy divergence** — that bin presupposes both pipelines reach a failure path
  and handle it differently. Here the Java pipeline *succeeds* on this input; the Kotlin one
  fails a layer earlier, at input decoding.
- **Not a spike artifact** — Task 3 wrote no CharStream or lexer code. `VimRegexParser.kt:34`
  is a one-line call into antlr-kotlin's own library, exactly what a real port would do.

The source-level trace above is of the **JVM** artifact. The JS runtime's `StringCharStream` was
never decompiled or inspected; the attribution on that target is inferred from identical
observable behavior (`appendix/task-5-report.md:88-91` is explicit about this). The two targets
share a source tree upstream, so a common root cause is the strong reading — but it is inference,
not a second independent trace.

It was independently reproduced by a second agent compiling its own probe against the runtime
jar, with matching numbers. It reproduces on Node with the same error code and a *byte-for-byte
identical* assertion message (`Expecting successful parsing for pattern 🨤 but got E383`),
differing only in stack-frame machinery. That identity is the important part: **one consistent
upstream bug, not two runtimes diverging from each other.** A JVM/JS asymmetry would have been
a far worse result than a shared defect.

Practical impact: a Vim pattern whose *last* codepoint is outside the Basic Multilingual Plane
— an emoji or a rare CJK character at the end of a search — misparses. Every other
Unicode-adjacent test passes: BMP characters, `[\u0-￿]` code ranges, Cyrillic collections,
nomagic-mode Unicode.

The fix must land upstream in antlr-kotlin, or be worked around locally by wrapping/replacing
the `CharStream`. It was deliberately not fixed in the spike.

---

## 4. What this does *not* prove

A gate report that overclaims is worse than one that reports a weaker result honestly. Read
this section before acting on section 1.

**The ported visitors were never executed.** `MultiVisitor` and `CollectionElementVisitor` are
**compile-verified only**. `VimRegexParser.parse()` — the sole entry point the tests exercise
— builds and returns the raw `ParseTree` and never invokes a visitor. No code path anywhere in
this spike runs either class. Task 2 did verify that raw `visitor.accept()` dispatch and
`ParseTreeWalker` tree-walking work correctly on both targets, with balanced enter/exit counts
— but with a throwaway harness, not with IdeaVim's real visitors. Relatedly, the
`defaultResult()` overrides those two visitors needed were implemented as `error(...)` throws,
on the reasoning that correct parses never reach them; that reasoning is untested.

**More than half the parser subpackage was pruned.** `PatternVisitor.kt` — **761 lines, 54% of
the 1,406-line `parser/` subpackage** — was deleted, because it imports ~30 classes from the
NFA/matcher engine half that the spike also deleted (`regexp/engine/`, `regexp/match/`,
`CharPointer.kt`, `VimRegex.kt`). It is functionally engine code filed under
`parser/visitors/`. **Nothing downstream of parse-tree construction was tested at all.** This
gate answers "does IdeaVim's regex grammar and parse-tree construction work on antlr-kotlin",
not "does IdeaVim's regex engine work on antlr-kotlin".

**The Vimscript corpus is bounded by IdeaVim's own test suite.** All 1,865 commands were
extracted from `enterCommand("…")` call sites in `src/test`. That is real-world usage, curated
by IdeaVim's own engineers — it is *not* an exhaustive fuzz of the grammar, and may
under-exercise deeply nested `function`/`try`/`for` blocks, multi-line constructs, or unusual
Unicode. 0/1,865 is a strong result about IdeaVim's known Vimscript surface, not a proof of
total grammar equivalence.

**The Vimscript differential ran on the JVM only.** The corpus test lives in `jvmTest`. The
generated Vimscript parser compiles for JS (verified in §1.2), but its *parse behavior* on
Node was never compared against anything. The regex grammar has JVM/JS behavioral parity
evidence; the Vimscript grammar does not.

**The performance numbers have no persisted artifact.** Two cold runs, no warmup, no JIT
priming, no averaging, one representative pattern (`\(foo\|bar\)\{2,5}[a-z]*\$`, parsed 1,000
times). JVM alone varied 203.8 ms → 290.5 ms between runs — **the JVM's own run-to-run
variance (~40%) exceeded the JVM-vs-JS gap in either direction**. Treat "≈1×" as directional
evidence of no order-of-magnitude cliff, nothing more. Real numbers need a proper harness, and
per spec §2 that work belongs to sub-project 2 anyway.

**`Char.isJavaIdentifierPart()` was approximated, and the approximation changes behavior.**
It never executed during any test in this spike, so it did not affect any reported result —
but it is an open problem, not a solved one.

**One corpus-harness defect was found and fixed mid-flight.** The plan's extraction script had
a dead-code bug (a 2-character dict key that could never match a 1-character lookup), leaving
`\\` un-unwound and feeding malformed double-backslash input for **24 of 1,865** entries
(1.3%). Task 6 found it by inspection *before* generating the golden file, so the 0/1,865
result is clean. The plan document carried the bug until commit `0caaaab9e`, which fixed the
dead key and added a comment explaining why it could never match. The corpus also came out at 1,865 lines against the
plan's expected 1,700–1,815, traced to test-suite growth since the plan's baseline, not to an
extraction fault.

---

## 5. Recommendation — applying the decision rule

The rule, from the plan:

> - **GO** — both grammars generate; **all `VimRegexParserTest` assertions pass on both
>   targets**; corpus divergence rate under ~1% and every divergence explained.
> - **GO WITH CAVEATS** — the above holds except for a bounded, enumerated set of divergences
>   with known workarounds. List each caveat as a task to fold into phase 1.
> - **NO-GO** — a grammar fails to generate, error-strategy behavior cannot be reproduced,
>   JVM/JS divergences are unexplained, or the corpus divergence rate is high enough that
>   Vimscript compatibility is in question.

Taken clause by clause:

| Clause | Status |
|---|---|
| Both grammars generate | ✅ Met — all three `.g4` files generate; two needed edits, `Vimscript.g4` needed none |
| **All `VimRegexParserTest` assertions pass on both targets** | ❌ **Not met.** 63 of 64 on each target |
| Corpus divergence rate under ~1%, every divergence explained | ✅ Met — 0.0% (0/1,865); nothing to explain |
| Error-strategy behavior reproduced | ✅ Met — 0 error-strategy divergences on either grammar |
| JVM/JS divergences unexplained | ✅ None exist — the two targets produce *identical* results, including the identical failure |

**A literal reading of the rule does not yield GO.** One assertion fails. I am not rounding
that up.

It is equally not a NO-GO. Checked against the NO-GO triggers: no grammar failed to generate;
error-strategy behavior was reproduced exactly; there are **zero** JVM/JS divergences, let
alone unexplained ones; and the corpus divergence rate is zero, so Vimscript compatibility is
not in question. None of the four NO-GO conditions is met, and manufacturing one for symmetry
would misrepresent the evidence.

**The evidence supports GO WITH CAVEATS**, and the single failure fits that branch's
description precisely — "a bounded, enumerated set of divergences with known workarounds":

- **Bounded.** Exactly one failure, in one named test, on one named input class: a pattern
  whose final codepoint lies outside the BMP.
- **Understood.** Root-caused to a specific function (`StringCharStream.codePoint()`) in a
  specific version (1.0.13), reproducible through the bare CharStream API with no grammar
  involved, and independently reproduced by a second agent.
- **Not a defect in what the gate was testing.** It is not a grammar divergence and not an
  error-strategy divergence — the two failure modes that would have genuinely undermined the
  approach. The gate's central question was whether antlr-kotlin can reproduce ANTLR's Java
  semantics for IdeaVim's grammars, including the error-strategy subclassing the spec flagged
  as highest risk. On that question the answer is a clean yes: 63/63 grammar and error-strategy
  assertions, 1,865/1,865 parse trees.
- **Has known workarounds.** Fix upstream in antlr-kotlin, or wrap/replace the `CharStream`
  locally. Neither requires touching a grammar or abandoning the approach. **This is the
  weakest leg of the argument and should be read as such:** neither workaround was
  *demonstrated*. Nobody wrote a wrapping `CharStream` and watched the test go green, and the
  upstream fix is outside IdeaVim's control with no timeline. The rule says "known", not
  "exercised", so this is literally satisfied — and the root cause is precise enough (a decode
  failure at end-of-stream, behind a small interface) that a local wrapper is clearly tractable.
  But caveat 4 lists "accept the limitation and document it" as a real disposition, which
  concedes this may simply be lived with.
- **Argues *for* the approach, in one respect.** The failure being byte-for-byte identical on
  JVM and Node is evidence of a single consistent runtime rather than two runtimes that drift
  apart — which is exactly the property the Multiplatform approach depends on.

**Verdict: GO WITH CAVEATS.** Phases 1–5 may proceed as specified in the design spec. The
sidecar approach does not need to be reopened.

The honest framing of the residual risk: this gate cleanly de-risks the *parser layer*, which
was the spec's top-listed risk (W1), and de-risks it more thoroughly than expected on the
error-strategy sub-risk specifically. It does **not** de-risk the regex *engine* (untested),
the visitors (unexecuted), or the JDK-substitution workstream — and on W4 it produced mildly
*negative* evidence, since the very first non-JVM compile of `vim-engine` source hit a
behavior-changing substitution that the spec had characterised as trivial.

---

## 6. Caveats — tasks to fold into phase 1

Seven. Each is a concrete piece of work, not a note.

**Caveat 1 — Decide the grammar-sharing policy.** `RegexLexer.g4`'s Java `@members` block and
8 inline actions must be hand-translated to Kotlin, and `RegexParser.g4` needs the label
rename (caveat 2). So the `.g4` files cannot be shared byte-for-byte between the Java build and
the Kotlin build. Choose one:
  (a) maintain a second, hand-synced Kotlin copy of each grammar carrying embedded actions —
  ongoing divergence risk, forever; or
  (b) move all stateful lexer behavior out of grammar actions entirely (an external
  listener/interceptor), so the `.g4` files need no target-language code and can be shared —
  higher up-front cost, no ongoing tax; or
  (c) migrate the real grammars to Kotlin outright and drop the Java build's copy.
  `Vimscript.g4` is unaffected either way; it needed no edits at all.
  Recommendation: (b) or (c). (a) is the option that quietly costs the most.

**Caveat 2 — Rename `start=` in the real grammar and audit all three for reserved-member
collisions.** Apply `start=` → `rangeStart=` in `vim-engine/src/main/antlr/RegexParser.g4:158`,
and update the ripple at `CollectionElementVisitor.kt:27` (`ctx.start.text` →
`ctx.rangeStart.text`). Then grep all three grammars for labels named `children`, `start`,
`stop`, `exception`, `payload`, or `parent` — those six are `final` members of the Kotlin
runtime's `ParserRuleContext` and cannot be overridden. Make this a standing check on grammar
changes, not a one-off: Java hides these fields silently, so nothing warns you today.

**Caveat 3 — Budget the antlr-kotlin API-difference sweep.** 4 distinct root causes, 35 sites,
in 672 lines. Root cause 2 (Java platform-type nullness vs. Kotlin's exact-match override rule)
accounts for 28 of the 35 (80%) and scales with **every `visitXyz` override on every ANTLR visitor
in `vim-engine`** — `ExecutableVisitor`, `ExpressionVisitor`, `ScriptVisitor`,
`CommandVisitor`, `PatternVisitor`, `MultiVisitor`, `CollectionElementVisitor`. Plausibly
hundreds of mechanical signature edits. Also expect: `defaultResult()` must be implemented on
every concrete visitor (abstract in the Kotlin runtime, has a body in Java), and `Token?` /
`Token.text: String?` nullability at every context-label read site. Do not assume the Kotlin
runtime is "Java plus `?` everywhere" — one signature went the other way.

**Caveat 4 — Resolve the astral-plane `CharStream` bug.** Report
`StringCharStream.codePoint()` upstream to antlr-kotlin with the three-input reproduction in
§3. Decide the local disposition: accept the limitation and document it, wrap/replace the
`CharStream`, or carry a patched runtime. Add a regression test on the exact input so the fix
is verifiable. This is the one open behavioral defect from the gate; it should not be allowed
to become folklore.

**Caveat 5 — Re-scope W4 as behavior-affecting, and sweep `vim-engine` for JVM-only stdlib
calls.** `Char.isJavaIdentifierPart()` needs a real multiplatform implementation, not the
spike's `isLetterOrDigit() || '_' || '$'` approximation, which changes `\i` / `[:ident:]`
matching for currency symbols and certain Unicode categories. More importantly: this was found
in the first 672 lines of `vim-engine` anyone compiled for a non-JVM target, out of ~79k.
Sweep for the rest **before** phase 3 costs W4, and update the spec's characterisation of W4
from "trivial substitutions" — at least one substitution changes behavior.

**Caveat 6 — Nothing downstream of parse-tree construction has been proven.** `PatternVisitor`
(761 lines) and the entire regex NFA/matcher engine were pruned from the spike, and the two
ported visitors were never executed. Phase 1 should treat "port `PatternVisitor` and the
matcher engine, and run IdeaVim's regex *matching* tests on both targets" as its own tracked
work item with its own gate — not as a mopping-up detail. It is the largest untested surface
this gate leaves behind.

**Caveat 7 — Fix the corpus harness before reusing it, and extend it to JS.** Two items:
(i) the golden file's tab-delimited `input\ttree` format breaks if any corpus input ever
contains a literal tab (verified zero today, so this is latent, not live); (ii) the Vimscript
differential ran on the JVM only — re-run it on Node before phase 4's gate, since the Vimscript
grammar currently has compilation evidence on JS but no behavioral evidence.
(The plan's dead-key extraction bug was a third item here; commit `0caaaab9e` fixed it in the
plan document, so it is closed.)

**Minor, tracked but not blocking:** the antlr-kotlin generator emits `@Suppress("UNSAFE_CALL")`
at 4 sites in `VimscriptParser.kt` (§1.2). It compiles on Kotlin 2.3.20; the Kotlin team
guarantees nothing about it across versions. Watch it on Kotlin upgrades and report upstream.

### Carried forward to later phases (not phase 1)

- **Phase 2, JUnit5 migration:** 21 of 653 files (3.2%) use constructs `kotlin.test` lacks —
  all attributable to `@ParameterizedTest`; zero files use `@Nested`, `@TestFactory`,
  `@RepeatedTest`, `@CsvSource`, or `@TestTemplate`. All 3 sampled files were mechanical
  rewrites (loop over the data instead of parameterizing). Sequence
  `src/testFixtures/kotlin/org/jetbrains/plugins/ideavim/util.kt:95`'s `productForArguments`
  **first** — its `List<Arguments>` return type blocks 10 of the 21 files at once, so fixing
  it unblocks 48% of the union in one change, and missing it makes 10 files fail together.
- **Phase 2, real sizing:** the annotation gap is the *small* part. 594 of 653 files (91%)
  extend `VimTestCase`, which is built on `CodeInsightTestFixture`, `LightProjectDescriptor`,
  `IdeaTestFixtureFactory` and similar IDE-only APIs. That is orthogonal to the test framework
  and is already the planned core of phase 2 (the headless in-memory host) — but any phase 2
  status report that leads with "21 files need work" will badly understate scope. Worth sizing
  explicitly how many `VimTestCase` subclasses are fixture-light in practice (the Vimscript
  parser tests, for instance, only call `VimscriptParser.parseCommand`) versus genuinely need
  editor/action fixtures.
- **Sub-project 2, performance:** re-measure with warmup, multiple patterns, and statistical
  reporting. The spike's numbers establish only "no order-of-magnitude cliff".

---

## 7. If NO-GO

**This branch was not taken.** Recorded for completeness, and so the reasoning is auditable.

A NO-GO would have required one of: a grammar failing to generate; error-strategy behavior
that could not be reproduced; JVM/JS divergences left unexplained; or a corpus divergence rate
high enough to put Vimscript compatibility in doubt. The measured values were, respectively:
all three grammars generate; zero error-strategy divergences; zero JVM/JS divergences; and a
0.0% corpus divergence rate.

Had it gone the other way, the response per the plan would have been to return to spec §1 and
reopen the engine-hosting decision in favour of the bundled-JVM sidecar driven over JSON-RPC —
accepting a ~50MB bundled JRE, platform-specific VSIXs, no web VS Code, and two permanent
runtimes, in exchange for reusing `vim-engine` untouched.

What would still reopen that decision later, and is worth watching:

- The astral-plane `CharStream` bug (caveat 4) proving to be one instance of a broader class of
  Unicode-handling defects in antlr-kotlin's runtime, rather than a single localized bug.
- Porting `PatternVisitor` and the NFA/matcher engine (caveat 6) surfacing divergences of a
  kind this gate did not see — this is untested surface, and it is large.
- The API-difference sweep (caveat 3) turning out to require judgment rather than mechanical
  edits at scale. Every one of the 35 sites found here was mechanical; that should hold, but it
  is an extrapolation from 672 lines to ~79k.

Note that the measurements in this phase — the 1,865-command corpus harness and the JUnit5
sizing in particular — remain valid inputs to the sidecar approach as well, and would not be
wasted.

---

## Appendices

The seven per-task reports are preserved verbatim in `findings/appendix/`, ~1,850 lines total.
They are the primary evidence behind every figure above, and contain material this report
summarises rather than reproduces — most notably Task 3's full porting-friction log with exact
compiler diagnostics, and Task 2's `javap`-derived reserved-member analysis.

| File | Contents |
|---|---|
| `appendix/task-1-report.md` | Toolchain scaffolding; proof both test runners genuinely execute |
| `appendix/task-2-report.md` | Grammar generation; the `start=` collision, root-caused; reserved-member list; runtime dispatch/tree-walking check |
| `appendix/task-3-report.md` | **The porting-friction log** — every fix, with exact compiler error text and classification |
| `appendix/task-4-report.md` | Regex parser tests on JVM; the astral-bug root-cause investigation |
| `appendix/task-5-report.md` | Same suite on Node; the JVM/JS comparison; performance probe |
| `appendix/task-6-report.md` | Vimscript differential over the 1,865-command corpus; the extraction-script bug |
| `appendix/task-7-report.md` | JUnit5 → `kotlin.test` migration sizing |

The spike build lived at `spike/antlr-kotlin/` and **has been deleted**, per the plan's
throwaway policy, now that this finding is recorded. It remains fully recoverable from git
history at commits `01d792fdc` … `8ded495a5` — `git checkout 8ded495a5 -- spike/antlr-kotlin`
restores a working antlr-kotlin build if phase 1 wants a reference. `findings/` is what survives
in the working tree.

`appendix/controller-rulings.md` records the seven controller rulings made during the run —
decisions that overrode or amended the plan, each with its rationale and its cost-if-wrong.
Rulings 4, 5 and 6 are load-bearing on this report: Ruling 4 is the origin of caveat 2,
Ruling 5 is why these appendices exist at all, and Ruling 6 created the runtime/library
divergence bin used in §3.
