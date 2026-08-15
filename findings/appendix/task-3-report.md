# Task 3 Report: Port the regex parser subpackage onto the Kotlin runtime

## Status: DONE

Both `compileKotlinJvm` and `compileKotlinJs` succeed against the ported code.

## What I ported

Copied `vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp/` into
`spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/`, then deleted
everything outside `parser/` per Step 1 (the NFA/matcher engine half:
`engine/`, `match/`, `CharPointer.kt`, `VimRegex.kt`, `VimRegexException.kt`,
`VimRegexOptions.kt`). `VimRegexErrors.kt` was replaced with the brief's minimal stub
rather than deleted, since `parser/` references it.

The `parser/` subpackage as it exists in `vim-engine` today has **9 files, 1,406 lines**
(confirmed via `wc -l`), not the brief's stated "14 files" — I could not find a 14-file
grouping that matches; the "~1,406 lines" figure does match exactly, so I read this as a
miscount in the brief rather than a sign I'm missing files, and ported the full 9.

Of those 9, **8 shipped** (672 lines) and **1 was pruned** (see below):

- `parser/BracketNormalizer.kt` — unchanged (no ANTLR imports)
- `parser/VimRegexParser.kt`
- `parser/VimRegexParserResult.kt`
- `parser/error/BailErrorLexer.kt`
- `parser/error/VimRegexParserErrorStrategy.kt`
- `parser/error/VimRegexParserException.kt` — unchanged (no ANTLR imports)
- `parser/visitors/CollectionElementVisitor.kt`
- `parser/visitors/MultiVisitor.kt`

Plus the new stub `regexp/VimRegexErrors.kt` (enum with only `E383` — confirmed via
`grep -rho 'VimRegexErrors\.[A-Z0-9]*'` across the whole copied tree, before and after
pruning; nothing else is referenced).

## What I pruned, and why: `parser/visitors/PatternVisitor.kt` (761 lines)

This is the largest single decision in this task, so I want to be explicit about the
reasoning rather than bury it in the fix log.

`PatternVisitor.kt` lives under `parser/visitors/`, so Step 1's directory-based deletion
(everything outside `parser/`) does not touch it. But it imports ~30 classes from
`regexp.engine.nfa.NFA` and `regexp.engine.nfa.matcher.*` — the exact "NFA/engine half"
Step 1 says is out of scope and deletes. It's the file that walks the parse tree and
*builds* the NFA, i.e. functionally engine code that happens to be filed under
`parser/visitors/`.

Before deleting anything, I checked whether the task's actual required interface —
`VimRegexParser.parse(pattern: String): VimRegexParserResult` — needs it. It doesn't:
`VimRegexParser.kt` never references `PatternVisitor`, and `VimRegexParserResult.Success`
only holds a `ParseTree`, not an NFA. I grepped the *original* `vim-engine` sources for
`PatternVisitor` outside `parser/visitors/` and found exactly one caller:
`regexp/VimRegex.kt:86-87` (`PatternVisitor.visit(parseResult.tree)`) — itself part of the
deleted engine half.

I ran the first compile attempt with `PatternVisitor.kt` still present anyway, to get
genuine first-attempt failure evidence per Step 4 (see friction item 1 below), then applied
Step 4's own rule 1 ("residual references to deleted engine classes → delete the
referencing file if it is engine-side") to remove it. `MultiVisitor` and
`CollectionElementVisitor` are both used *by* `PatternVisitor` (confirmed via grep) but
have no engine dependency of their own, so they ported clean. They are **not** called by
`VimRegexParser.parse()` — confirmed by reading `VimRegexParser.kt` in full, it builds and
returns the `ParseTree` directly and never touches a visitor. I left both classes in
anyway, per the brief's Step 1 instruction to port the parser subpackage, since they
compile cleanly and cost nothing to keep.

**Correction (fix round 1):** I previously told the team lead in chat that "Task 4 will be
the first to exercise [the visitors] behaviorally" — that's wrong, and worth stating
plainly here rather than just in a chat message. **`MultiVisitor` and
`CollectionElementVisitor` are compile-verified only and are never executed anywhere in
this spike.** `VimRegexParser.parse()` — the only entry point this task's interface
requires, and the only thing `VimRegexParserTest` can exercise per the brief's Interfaces
section — never invokes a visitor at all; it returns the raw `ParseTree`. Task 2 did verify
raw `visitor.accept()` / `ParseTreeWalker` dispatch works on both targets, but with a
throwaway harness, not with IdeaVim's real visitor implementations. No code path in this
repository currently runs these two ported visitors.

**Task 4 needs to know:** the ported code answers "does IdeaVim's regex *grammar and
parse-tree construction* work on antlr-kotlin," not "does the whole regex engine," and not
even "do the ported visitors work" — they're untested code, present because they compile
and the brief said to port the subpackage, not because anything currently calls them. If
`VimRegexParserTest` only exercises `VimRegexParser.parse()` and inspects
`VimRegexParserResult`, this spike is sufficient for that. If it expects to also run
`VimRegex` end-to-end, or to exercise `MultiVisitor`/`CollectionElementVisitor` directly,
that's out of scope here and was never in Task 3's brief.

## Commands run

```
$ mkdir -p spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim
$ cp -R vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/
$ find . -maxdepth 1 -type f -name '*.kt' -delete   # (inside regexp/)
$ find . -maxdepth 1 -type d ! -name . ! -name parser -exec rm -rf {} +
$ find spike/antlr-kotlin/src/commonMain -name '*.kt' -exec sed -i '' 's/org\.antlr\.v4\.runtime/org.antlr.v4.kotlinruntime/g' {} +
$ grep -rho 'VimRegexErrors\.[A-Z0-9]*' spike/antlr-kotlin/src/commonMain | sort -u
VimRegexErrors.E383
# wrote VimRegexErrors.kt stub with only E383, per brief
# applied ctx.start.text -> ctx.rangeStart.text per team-lead instruction
$ ./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs --console=plain
# FAILED — ~100 errors, all in PatternVisitor.kt, all "Unresolved reference" to
# deleted NFA/matcher classes
$ rm spike/antlr-kotlin/.../parser/visitors/PatternVisitor.kt
$ ./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs --console=plain
# FAILED — 19 distinct errors across 4 files (see friction log)
# ... applied fixes ...
$ ./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs --console=plain
# FAILED — 1 error: Char.isJavaIdentifierPart() unresolved on Kotlin/JS only
# (compileKotlinJvm had already succeeded by this point)
# ... applied fix ...
$ ./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs --console=plain
BUILD SUCCESSFUL in 2s
5 actionable tasks: 5 executed
$ ./gradlew -p spike/antlr-kotlin clean compileKotlinJvm compileKotlinJs --console=plain
BUILD SUCCESSFUL in 2s
8 actionable tasks: 7 executed, 1 up-to-date
```

## The porting-friction log (primary deliverable)

Note on the brief's `findings/2026-08-16-antlr-kotlin-gate.md`: that path doesn't exist in
the repo, and Task 2 (whose report I read first) didn't create it either — it put the full
friction log in its own task report instead, which is also what the team lead's Report
Format instructions to me ask for. I followed that precedent and put everything here
rather than inventing a `findings/` directory that no prior task used.

### 1. `PatternVisitor.kt` — ~100 errors, all "Unresolved reference" to `NFA`, `CharacterMatcher`, `EndOfLineMatcher`, etc.

**Classification: (c) artifact of the spike's stubbing.** Expected and predicted by the
brief itself (Step 1's "the NFA/engine half... is not needed"; Step 4's rule 1). Not a
runtime API difference — these classes simply don't exist in this spike's source set.
**Fix:** deleted the file (761 lines). See the "What I pruned" section above for the full
reasoning on why this is safe with respect to the required `VimRegexParser.parse()`
interface.

### 2. `VimRegexParser.kt:38` — `parser.errorListeners.clear()` unresolved

```
e: ...VimRegexParser.kt:38:29 Unresolved reference. None of the following candidates is
applicable because of a receiver type mismatch
```

**Classification: (a) genuine `org.antlr.v4.runtime` vs `org.antlr.v4.kotlinruntime` API
difference.** Confirmed via `javap` on `antlr-kotlin-runtime-jvm-1.0.13.jar`:
`Recognizer.getErrorListeners()` returns a plain `List<ANTLRErrorListener>` (no `.clear()`
available on the Kotlin `List` interface); the runtime instead exposes
`removeErrorListeners(): Unit` directly on the recognizer.
**Fix:** `parser.errorListeners.clear()` → `parser.removeErrorListeners()`.

### 3. `VimRegexParserErrorStrategy.kt` — `recover`, `recoverInline`, `sync` all "overrides nothing"

```
e: ...VimRegexParserErrorStrategy.kt:19:3 'recover' overrides nothing. Potential signatures:
fun recover(recognizer: Parser, e: RecognitionException): Unit
e: ...VimRegexParserErrorStrategy.kt:23:3 'recoverInline' overrides nothing. Potential signatures:
fun recoverInline(recognizer: Parser): Token
e: ...VimRegexParserErrorStrategy.kt:27:3 'sync' overrides nothing. Potential signatures:
fun sync(recognizer: Parser): Unit
```

**Classification: (a) genuine API difference — and notably the *opposite* of Task 2's
prediction.** Task 2's report flagged "nullability of `Parser?` in
`DefaultErrorStrategy.recover`" as an anticipated difference. Confirmed via `javap` on
`DefaultErrorStrategy.class`: all three methods take a **non-null** `Parser`, whereas the
ported vim-engine code (written against the Java runtime, where all reference types are
Kotlin "platform types") declared them as `Parser?`. Kotlin requires exact parameter-type
matches for overrides, so `Parser?` doesn't override `Parser`.
**Fix:** dropped the `?` on all three `recognizer: Parser?` parameters.

**Same root cause as item 4 below** — both are "a method on a Java class arrives in Kotlin
as an unenforced platform type, so vim-engine was free to declare the override parameter
nullable; the pure-Kotlin runtime declares it strictly non-null and Kotlin's exact-match
override rule then rejects the nullable signature." Kept as a separate log entry here
because the file, method set, and fix are distinct, but see item 4 and the "Category (a)
count" section below for why these are now classified together.

### 4. `CollectionElementVisitor.kt` (11 methods) / `MultiVisitor.kt` (6 methods) — `visitXyz` "overrides nothing" wherever `ctx: XyzContext?` (nullable) was used

```
e: ...CollectionElementVisitor.kt:33:3 'visitAlnumClass' overrides nothing. Potential
signatures for overriding: fun visitAlnumClass(ctx: RegexParser.AlnumClassContext): Pair<...>
```
(and 10 more identical-shaped errors in that file; 6 more in `MultiVisitor.kt` for
`visitZeroOrOne`, `visitAtomic`, `visitPositiveLookahead`, `visitNegativeLookahead`,
`visitPositiveLookbehind`, `visitNegativeLookbehind`)

**Classification: (a) genuine API difference.** *(Reclassified from (b) — see "Category (a)
count" below for what changed and why. This entry originally argued the distinction was
"the existing code's style against a Java target," treating it as language semantics rather
than a runtime API difference. Code review caught that this is the identical mechanism as
item 3, just at a different call site: a method on a Java class (here,
`AbstractParseTreeVisitor<T>.visitXyz`) arrives in Kotlin as an unenforced platform type, so
vim-engine was free to declare `ctx: XyzContext?`; the pure-Kotlin
`org.antlr.v4.kotlinruntime.tree.AbstractParseTreeVisitor<T>` declares the same parameter
as strictly non-null `ctx: XyzContext`, and Kotlin's exact-match override rule (no
contravariant relaxation of nullability, unlike the covariance allowed on return types)
rejects the nullable signature. There is no technical distinction between this and item 3
that justifies a different bin — both are "Java platform-type nullness vs. exact-match
Kotlin overrides," so both are (a).)*
**Fix:** stripped the trailing `?` from every `ctx: XyzContext?` parameter in both files
(17 total), via `sed -E 's/(ctx: RegexParser\.[A-Za-z0-9_]+Context)\?/\1/g'` then verified
no `Context?` occurrences remained.

**Scale note for Phase 1:** this is 17 sites in this task alone — more than item 3's 3 —
and it is not specific to these two visitor classes. Every `visitXyz` override on every
ANTLR visitor anywhere in vim-engine that was written with a nullable `ctx` parameter (a
free, Java-platform-type-enabled style choice with no compile-time consequence on the JVM
target) will hit this exact wall when ported. The real port has substantially more visitor
classes than the two here (`ExecutableVisitor`, `ExpressionVisitor`, `ScriptVisitor`,
`CommandVisitor`, `PatternVisitor`, plus this task's `MultiVisitor` and
`CollectionElementVisitor`), each with many `visitXyz` overrides — this is plausibly
hundreds of signature edits across the full port, not a handful. It is mechanical
(strip-the-`?`, recompile, repeat) but it is real, countable Phase 1 cost, not free
language-semantics noise.

### 5. `CollectionElementVisitor` / `MultiVisitor` — "Class is not abstract and does not implement abstract base class member: `fun defaultResult(): T`"

```
e: ...CollectionElementVisitor.kt:19:10 Class 'CollectionElementVisitor' is not abstract
and does not implement abstract base class member: fun defaultResult(): T
e: ...MultiVisitor.kt:22:10 Class 'MultiVisitor' is not abstract and does not implement
abstract base class member: fun defaultResult(): T
```

**Classification: (a) genuine API difference**, generalizing a finding Task 2's addendum
already made for a `Unit`-returning smoke-test visitor — confirmed here for two
non-`Unit` result types (`Pair<CollectionElement, Boolean>` and `Multi`), so it's not
`Unit`-specific. Java's `AbstractParseTreeVisitor<T>.defaultResult()` has a body
(`return null`), so Java/JVM-target Kotlin subclasses never need to override it. The
Kotlin runtime's `defaultResult()` is `abstract`, so **every** concrete visitor subclass
must implement it, even when — as in both these visitors — every real grammar alternative
already has an explicit `visitXyz` override and `defaultResult()` is structurally
unreachable in practice.
**Fix:** added `override fun defaultResult(): T = error("...")` to both classes (throws
rather than fabricating a bogus success value, since correct parses never reach it).

### 6. `CollectionElementVisitor.kt:27-28` — `ctx.rangeStart.text` / `ctx.end.text` nullability

```
e: ...CollectionElementVisitor.kt:27:39 Argument type mismatch: actual type is 'String?', but 'String' was expected.
e: ...CollectionElementVisitor.kt:27:53 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'Token?'.
```

**Classification: (a) genuine API difference** — exactly what Task 2's report predicted
("Generated context accessors return nullable `Token?` where the Java runtime returned
non-null"), now confirmed at an actual call site, and confirmed to compound: **both** the
token-label field (`rangeStart`/`end`: `Token?`) **and** `Token.text` (`String?`) are
nullable in the Kotlin runtime.
**Fix:** `cleanLiteralChar(ctx.rangeStart!!.text!!)` / `cleanLiteralChar(ctx.end!!.text!!)`.
This is also the exact line the team lead's carry-over instruction covers (`ctx.start` →
`ctx.rangeStart`, required by Task 2's `RangeColElem` label rename) — I applied that rename
first, then hit this nullability issue independently on the very next compile.

### 7. `MultiVisitor.kt:54,57` — `lowerBoundToken.text.toInt()` / `upperBoundToken.text.toInt()` nullability

```
e: ...MultiVisitor.kt:51:126 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.
e: ...MultiVisitor.kt:54:29 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.
```

**Classification: (a) genuine API difference** — same `Token.text: String?` nullability as
item 6, different call site (already-nullable `Token?` locals here, so only `.text` needed
the assertion, not the receiver).
**Fix:** `.text.toInt()` → `.text!!.toInt()` at both sites.

### 8. `CollectionElementVisitor.kt:110` — `it.isJavaIdentifierPart()` unresolved, **JS only** (JVM had already compiled clean)

```
e: ...CollectionElementVisitor.kt:110:65 Unresolved reference 'isJavaIdentifierPart'.
```

**Classification: doesn't cleanly fit any of the three given buckets** — flagging that
explicitly rather than forcing it into one. This has nothing to do with ANTLR or the
Kotlin/Java runtime split; `Char.isJavaIdentifierPart()` is a JVM-only extension in
Kotlin's standard library (`kotlin.text` JVM actual), unavailable on any other Kotlin
Multiplatform target. It's a **pre-existing JVM-only API usage in vim-engine itself**,
surfaced only because this is the first time any of its source has been compiled for a
non-JVM target. This is a real, general cost of the eventual Multiplatform port,
independent of antlr-kotlin: **any** JVM-only stdlib call anywhere in vim-engine will hit
this same wall on Kotlin/JS, and this task only found the one instance that happened to be
in the 672 lines it touched — there is very likely more elsewhere in vim-engine.
**Fix (spike-only approximation, not a faithful port):**
`it.isLetterOrDigit() || it == '_' || it == '$'`, with an inline comment noting it's not an
exact match for the JDK's identifier-part rules (e.g. currency symbols and Unicode
identifier-ignorable characters differ) and needs a real multiplatform-safe implementation
if this code leaves spike status. **Task 4 should know**: any test in `VimRegexParserTest`
that exercises `\i` / `[:ident:]` character-class matching against non-ASCII-identifier
input may see different results here than against `vim-engine`'s real behavior, because
this is a deliberately inexact substitute, not a nullability/signature translation like the
other seven items.

## Category (a) count

**Changed from the first version of this report** (fix round 1, applied after code review):
item 4 (visitor `ctx: XyzContext?` overrides) was originally binned **(b)** on the
reasoning that it was "the existing code's style against a Java target." Review argued —
and I agree, on re-examination — that this is the same mechanism as item 3
(`DefaultErrorStrategy.recover`'s `Parser?` → `Parser`), which was already binned (a):
both are a Java class's method arriving in Kotlin as an unenforced platform type, letting
vim-engine declare the override parameter nullable, versus the pure-Kotlin runtime
declaring it strictly non-null and Kotlin's exact-match override rule rejecting the
mismatch. There is no technical basis for binning one (a) and the other (b), so **item 4 is
now reclassified (a)**. Nothing else in the classification changed.

Reporting this **grouped by root cause**, since "distinct differences" and "sites affected"
diverge enough here to matter for Phase 1 costing — a single root cause (Java platform-type
nullness) accounts for 20 of the 27 category-(a) sites found in this task:

| # | Root cause | Sites in this task | Files |
|---|---|---|---|
| 1 | `Recognizer.errorListeners` has no `.clear()`; use `removeErrorListeners()` | 1 | `VimRegexParser.kt` |
| 2 | Java platform-type nullness vs. Kotlin's exact-match override requirement (items 3 + 4) | **20** (3 + 17) | `VimRegexParserErrorStrategy.kt`, `CollectionElementVisitor.kt`, `MultiVisitor.kt` |
| 3 | `AbstractParseTreeVisitor<T>.defaultResult()` is `abstract` in the Kotlin runtime, has a body in Java | 2 | `CollectionElementVisitor.kt`, `MultiVisitor.kt` |
| 4 | `Token`/context-label field and `Token.text` nullability (items 6 + 7) | 4 | `CollectionElementVisitor.kt`, `MultiVisitor.kt` |

**4 distinct root causes, 27 total category-(a) sites, in 672 lines of ported code.**
Root cause 2 is the one to flag loudest for Phase 1 costing: it scales with every
`visitXyz` override across every visitor class in the real port (see the scale note under
item 4 above), not with lines-of-code the way the others roughly do.

Item 1 (`PatternVisitor.kt` residual references) is (c) — not counted here. Item 8
(`isJavaIdentifierPart`) fits none of the three given buckets and is also not counted
here — see its own entry above.

## Files changed

```
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/VimRegexErrors.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/BracketNormalizer.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/VimRegexParser.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/VimRegexParserResult.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/error/BailErrorLexer.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/error/VimRegexParserErrorStrategy.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/error/VimRegexParserException.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/visitors/CollectionElementVisitor.kt
A  spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/visitors/MultiVisitor.kt
```

`vim-engine/`, `src/`, and the root `settings.gradle.kts` are untouched (`git diff --stat
-- vim-engine src` is empty).

## Anything that could NOT be ported

`parser/visitors/PatternVisitor.kt` — not ported, deliberately, per the "What I pruned"
section above. Everything else in the `parser/` subpackage ported successfully.

## Self-review findings

- `git status --short` before commit showed only the 9 new spike files plus the
  pre-existing untracked `.omc/` (not created by this task).
- `git diff --stat -- vim-engine src` is empty — nothing outside `spike/antlr-kotlin/`
  touched.
- Diffed every ported file against its `vim-engine` original (`diff -u`): confirmed
  `BracketNormalizer.kt` and `VimRegexParserException.kt` are byte-identical, and every
  other file's diff is exactly the import rewrite plus the specific fixes logged above —
  no incidental changes, no reformatting, no renamed identifiers beyond what compilation
  forced.
- Confirmed the required `ctx.start` → `ctx.rangeStart` change landed at
  `CollectionElementVisitor.kt:27` exactly as specified, and that no other `.start`/`.stop`
  usage in the ported files was touched (`BracketNormalizer.kt` doesn't use ANTLR context
  objects at all; no other file references `.start`/`.stop`).
- Copyright headers: all 8 copied files retain their original "Copyright 2003-2023"
  header verbatim (confirmed via the `diff` runs — none show a header change). The one new
  file I authored, `VimRegexErrors.kt`, uses the "2003-2026" header the brief's own
  template specified verbatim — I did not write my own header text.
- First commit's message got shell-mangled (backtick-quoted inline code inside the
  multi-line heredoc `-m` argument was executed as command substitution despite the quoted
  heredoc terminator, producing "command not found" noise and truncated content). Caught it
  immediately after the commit by reading it back with `git log -1 --format='%B'`, and
  fixed it with `git commit --amend -F <file>` (message-only change, no diff content
  touched, not yet pushed anywhere) rather than leaving a garbled message in history. Final
  commit: `5f2bc5dbb`.
- Re-ran `./gradlew -p spike/antlr-kotlin clean compileKotlinJvm compileKotlinJs` after the
  amend to confirm nothing was disturbed: `BUILD SUCCESSFUL`.

## Concerns for Task 4

1. **`PatternVisitor.kt` is not here, and `MultiVisitor`/`CollectionElementVisitor` are
   compile-verified only — nothing in this spike executes them.** `VimRegexParser.parse()`
   returns the raw `ParseTree` and never invokes a visitor. If `VimRegexParserTest` only
   calls `VimRegexParser.parse()` and asserts on `VimRegexParserResult`
   (`Success`/`Failure`, `ParseTree`, `CaseSensitivitySettings`), this ported code is
   sufficient. If it needs anything downstream of the parse tree (matching, NFA
   construction, `VimRegex`, or running the two ported visitors), that's categorically out
   of scope for what exists in this spike right now, and would be genuinely new ground —
   not yet compile-verified, let alone behavior-verified.
2. **`\i` / `[:ident:]` character-class behavior will differ from real IdeaVim** for any
   input where `Char.isJavaIdentifierPart()` and my
   `isLetterOrDigit() || it == '_' || it == '$'` approximation disagree — notably currency
   symbols and certain Unicode categories. This is the one fix in this task that changes
   *behavior*, not just types; everything else is type/signature-level only.
3. **The `defaultResult()` overrides throw (`error(...)`) rather than returning a value —
   but this is moot for Task 4 specifically**, since Task 4's real behavioral tests target
   `VimRegexParser.parse()`, which never invokes either visitor (see the correction above).
   This only matters for whoever, in a later task, first writes code that actually calls
   `MultiVisitor`/`CollectionElementVisitor`: if any grammar path I haven't considered ever
   causes ANTLR to call `defaultResult()` in practice (rather than it being purely
   structurally unreachable, as I believe from reading both visitors' full alternative
   coverage), that future caller will see a hard crash there instead of a silent wrong
   answer — that's intentional (fail loud rather than fabricate), but worth knowing ahead
   of time rather than discovering it as a mysterious `IllegalStateException`.
4. **Category (a) findings so far (Tasks 2+3 combined) are accumulating around a
   consistent theme**: nullability is stricter in the Kotlin runtime almost everywhere
   (`Token?` fields, `Token.text: String?`, visitor `defaultResult()` abstractness), while
   the one case that went the *other* direction (`DefaultErrorStrategy.recover`'s `Parser`
   parameter, non-null where Task 2 guessed nullable) shows the direction isn't uniformly
   predictable — Phase 1 shouldn't assume "the Kotlin runtime is just Java plus `?`
   everywhere" as a shortcut; each signature needs checking.

## Fix round 1 (report-only, no code changes)

Code review approved the ported code itself — every ported file was checked against its
`vim-engine` original with no drift found. The one finding was in this report, not the
code:

1. **Reclassified item 4 from (b) to (a).** Items 3 and 4 share one root cause (a Java
   class's method arriving in Kotlin as an unenforced platform type, letting vim-engine
   declare the override parameter nullable, versus the pure-Kotlin runtime declaring it
   strictly non-null and Kotlin's exact-match override rule rejecting the mismatch). There
   was no technical basis for binning them differently. Kept as two separate log entries
   (different files, different fix), both now marked (a), with an explicit cross-reference
   between them.
2. **Rewrote the "Category (a) count" section** to group by root cause instead of by log
   item, since "distinct differences" and "sites affected" diverge enough to matter for
   Phase 1 costing. Headline is now **4 distinct root causes, 27 total category-(a) sites**
   (previously stated as "5 distinct API differences" without a site count). The delta:
   item 4's 17 sites moved from uncounted-as-(a) to counted, which is the majority of the
   increase from the old framing.
3. **Added an explicit scale note** under item 4: this pattern recurs per `visitXyz`
   override across every visitor class in the real port (`ExecutableVisitor`,
   `ExpressionVisitor`, `ScriptVisitor`, `CommandVisitor`, `PatternVisitor`, plus this
   task's `MultiVisitor`/`CollectionElementVisitor`) — plausibly hundreds of signature
   edits across the full port, not a handful.
4. **Corrected an overclaim.** I told the team lead in chat that "Task 4 will be the first
   to exercise [the visitors] behaviorally" — wrong. `VimRegexParser.parse()` never invokes
   a visitor; it returns the raw `ParseTree`. `MultiVisitor` and `CollectionElementVisitor`
   are compile-verified only and are not executed anywhere in this spike. Corrected in the
   "What I pruned" section and in Concerns #1 and #3, which previously implied Task 4 would
   run these visitors.

**No code changed in this fix round, so no tests were re-run.** The build state from the
original report stands: `./gradlew -p spike/antlr-kotlin clean compileKotlinJvm
compileKotlinJs` → `BUILD SUCCESSFUL` (verified at commit `5f2bc5dbb`, unchanged by this
fix round).

## Report file
`/Users/neshkeev/projects/ideavim/.superpowers/sdd/2026-08-16-antlr-kotlin-gate/task-3-report.md`
