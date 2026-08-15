# Task 2 Report: Generate Kotlin regex parsers via antlr-kotlin

## Status: DONE (fixed per Ruling 4 — see addendum at the bottom of this report)

Original run below is left intact as gate evidence. See the addendum for the fix, the
ruling that authorized it, and the final passing results.

## Original status: BLOCKED (at Step 7 — jvmTest/jsNodeTest do not pass)

Steps 1–6 completed exactly as specified. ANTLR code generation (Step 5) succeeded and
produced all expected files. Step 7 (`jvmTest jsNodeTest`) fails: the antlr-kotlin
generator emits Kotlin that does not compile, on **both** targets, because of a grammar
construct in `RegexParser.g4` that collides with a member of the Kotlin runtime's
`ParserRuleContext`. Per instructions, I did not hand-edit generated code, modify the
grammar, or otherwise work around this — `RegexParser.g4` was required to stay
byte-identical and this is exactly the kind of gate signal the task said to record rather
than route around.

## What I implemented

1. **Step 1** — Copied `vim-engine/src/main/antlr/RegexLexer.g4` and `RegexParser.g4`
   into `spike/antlr-kotlin/antlr/`. `RegexParser.g4` is byte-identical to the original
   (verified with `diff`, no output).

2. **Step 2** — Translated the `@members` block in the spike's `RegexLexer.g4` from Java
   to Kotlin exactly as specified: `public Boolean ignoreCase = null;` →
   `var ignoreCase: Boolean? = null`, and the two setter functions renamed
   `setIgnoreCase`/`setNoIgnoreCase` → `markIgnoreCase`/`markNoIgnoreCase` (to avoid
   colliding with the Kotlin-generated `setIgnoreCase` accessor for the `ignoreCase`
   property). The property name `ignoreCase` itself was kept unchanged, as required.

3. **Step 3** — Updated all 8 inline lexer actions (`{ setIgnoreCase(); }` /
   `{ setNoIgnoreCase(); }`) to call the renamed functions, via the exact `sed` command
   given in the brief. Verified via `grep -n` that all 8 action sites (lines 106, 107,
   251, 252, 397, 398, 542, 543 — matching the brief's predicted line numbers exactly)
   were updated, and that `OT_IGNORE_CASE_VNOMAGIC` (the upstream `NOT_...` typo at line
   543) was left untouched apart from the function-call rename, as instructed.

   One small note: `grep -c 'mark(No)?IgnoreCase()'` reported `10`, not the `8` the brief
   expected — this is because the grep also matches the two function *definitions* in the
   `@members` block (`fun markIgnoreCase() {...}` / `fun markNoIgnoreCase() {...}`), which
   themselves contain the substring `markIgnoreCase()` / `markNoIgnoreCase()`. All 8 call
   sites were confirmed correct by explicit `grep -n` inspection, and no leftover
   `setIgnoreCase`/`setNoIgnoreCase` references remain anywhere in the file. This is a
   miscount in the brief's verification command, not a defect in the translation.

4. **Finding for Phase 1 (as requested in Step 3):** grammars with target-language
   embedded actions cannot be shared verbatim between the Java build (`vim-engine`) and
   a Kotlin Multiplatform build. Only `RegexLexer.g4` has this problem among the two regex
   grammars (its `@members` block and 8 inline actions), but the same issue will recur for
   any other grammar with embedded actions. Phase 1 must choose between (a) maintaining a
   second, hand-synced Kotlin copy of each grammar with embedded actions, or (b) moving
   all stateful lexer behavior out of grammar actions entirely (e.g. into an external
   listener/interceptor pattern) so the `.g4` files need no target-language code at all
   and can be shared byte-for-byte. This is a real, ongoing maintenance cost of the
   Multiplatform approach, independent of the Step 7 blocker below.

5. **Step 4** — Added the `com.strumenta.antlr-kotlin` Gradle plugin (`1.0.13`), the
   `generateKotlinGrammarSource` task, and wired `commonMain` to consume the generated
   sources plus the `antlr-kotlin-runtime` dependency, per the brief. One adjustment I
   made beyond the brief's snippet: I placed the `val generateKotlinGrammarSource = ...`
   task registration **before** the `kotlin { sourceSets { ... } } }` block in the file
   (the brief's snippet ordering, if followed literally top-to-bottom, would reference
   `generateKotlinGrammarSource` from inside `commonMain { }` before the `val` is
   declared later in the script — Gradle Kotlin DSL scripts evaluate top-to-bottom and
   `commonMain { }`'s body runs eagerly, so this would fail with an uninitialized-property
   error). Final file: `spike/antlr-kotlin/build.gradle.kts`.

6. **Step 5** — Ran `./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource`.
   **BUILD SUCCESSFUL.** See generated-file listing below.

7. **Step 6** — Added `spike/antlr-kotlin/src/commonTest/kotlin/GeneratedParserSmokeTest.kt`
   exactly as specified in the brief (entry rule `pattern`, confirmed correct against
   `RegexParser.g4` line 8), with the standard MIT copyright header.

8. **Step 7** — Ran `./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest`. **BUILD FAILED**
   — compilation of the generated `RegexParser.kt` fails on both `compileKotlinJvm` and
   `compileKotlinJs`, before any test can run. Full details below.

## Step 5 evidence — generated-file listing (gate evidence)

```
$ ./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource --console=plain
> Task :cleanGenerateKotlinGrammarSource UP-TO-DATE
> Task :generateKotlinGrammarSource

BUILD SUCCESSFUL in 9s
2 actionable tasks: 1 executed, 1 up-to-date

$ ls spike/antlr-kotlin/build/generatedAntlr/com/maddyhome/idea/vim/parser/generated/
RegexLexer.interp
RegexLexer.kt
RegexLexer.tokens
RegexParser.interp
RegexParser.kt
RegexParser.tokens
RegexParserBaseListener.kt
RegexParserBaseVisitor.kt
RegexParserListener.kt
RegexParserVisitor.kt
```

All expected files present (`RegexLexer.kt`, `RegexParser.kt`, `RegexParserBaseVisitor.kt`,
`RegexParserVisitor.kt`), plus listener variants and ANTLR metadata (`.interp`/`.tokens`)
that the brief didn't call out but are normal ANTLR-Kotlin output.

I spot-checked the generated `ignoreCase` translation landed correctly in
`RegexLexer.kt`:
```
648:        var ignoreCase: Boolean? = null
650:        fun markIgnoreCase() { ignoreCase = true }
651:        fun markNoIgnoreCase() { if (ignoreCase == null) ignoreCase = false }
671:                 markIgnoreCase()
679:                 markNoIgnoreCase()
... (8 call sites total, all four lexer modes)
```
and the entry rule:
```
$ grep -n 'fun pattern' .../RegexParser.kt
531:    public fun pattern(): PatternContext {
```

## Step 7 — the compile failure (this is the most valuable finding)

```
$ ./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest --console=plain

> Task :compileKotlinJvm FAILED
e: file:///.../spike/antlr-kotlin/build/generatedAntlr/com/maddyhome/idea/vim/parser/generated/RegexParser.kt:3728:48 'start' hides member of supertype 'ParserRuleContext' and needs an 'override' modifier.

> Task :compileKotlinJs FAILED
e: file:///.../spike/antlr-kotlin/build/generatedAntlr/com/maddyhome/idea/vim/parser/generated/RegexParser.kt:3728:48 'start' hides member of supertype 'ParserRuleContext' and needs an 'override' modifier.

FAILURE: Build completed with 2 failures.
BUILD FAILED in 9s
```

**Root cause, confirmed by inspection:**

`RegexParser.g4` line 158 (in the `collection_elem` rule, `RangeColElem` alternative) uses
a grammar label named `start`:
```
collection_elem : collection_char_class_expression                                                                 #CharClassColElem
                | start=(COLLECTION_LITERAL_CHAR | DASH | CARET) DASH end=(COLLECTION_LITERAL_CHAR | DASH | CARET) #RangeColElem
                | (COLLECTION_LITERAL_CHAR | DASH | CARET)                                                         #SingleColElem
                ;
```

ANTLR generates a field for every token label on the rule's context class. In the
generated `RegexParser.kt`:
```kotlin
public open class RangeColElemContext : Collection_elemContext {
    @JvmField @JsName("start$") public var start: Token? = null
    @JvmField @JsName("end$") public var end: Token? = null
    ...
```

`org.antlr.v4.kotlinruntime.ParserRuleContext` (the Kotlin runtime's base class,
confirmed via `javap` on `antlr-kotlin-runtime-jvm-1.0.13.jar`) already declares a
**`start`** member — the rule's start token:
```
private org.antlr.v4.kotlinruntime.Token start;
public final org.antlr.v4.kotlinruntime.Token getStart();
public final void setStart(org.antlr.v4.kotlinruntime.Token);
```

In Java (the current `vim-engine` build target), a subclass field can silently hide an
inherited field with no compile error — Java has no `override` concept for fields, and
this is exactly what happens today in `RangeColElemContext extends Collection_elemContext`.
In Kotlin, hiding an inherited member requires the `override` keyword, and the
antlr-kotlin generator does not add it (and, worse, **could not** add it even if it did:
`getStart`/`setStart` are `final` in the bytecode, meaning the Kotlin runtime's `start`
property was never declared `open`, so it is not overridable at all). This makes the
collision unresolvable without either renaming the grammar label or patching the
antlr-kotlin runtime/generator — both of which are outside what this task is permitted to
do.

I confirmed by grepping both grammar files that `start=` at line 158 of `RegexParser.g4`
is the **only** label anywhere in either grammar that collides with a `ParserRuleContext`
member name (checked against all public members visible via `javap`: `children`, `start`,
`stop`, `exception`, `position`, `childCount`, `sourceInterval`, `ruleContext`, `payload`,
`parent`). So this is a single, precisely-located defect, not a systemic one — but it is
a hard compile blocker as written, and it recurs identically on both `compileKotlinJvm`
and `compileKotlinJs` (same generated file, same line, both platforms).

**This is a genuine, reproducible antlr-kotlin generator limitation**, not a mistake in
translation or Gradle wiring: it fires on the very first grammar with a token label
matching a base-class member name, using a completely unmodified `RegexParser.g4` and the
antlr-kotlin versions pinned by this task (`1.0.13`). Per my instructions I did not:
- hand-edit `RegexParser.kt` to add or work around the collision,
- rename the `start=` label in `RegexParser.g4` (grammar was required to stay
  byte-identical),
- downgrade antlr-kotlin or the grammar in any other way.

## Generated-file listing (repeated for gate evidence, see above) — confirmed present

## Anything that did not translate cleanly (verbatim record)

1. **`@members` Java block** — required manual translation (Steps 2–3, done as specified;
   this was expected and planned for by the brief, not a surprise).
2. **`start=` label collision (the Step 7 blocker)** — described in full above. Exact
   error: `'start' hides member of supertype 'ParserRuleContext' and needs an 'override'
   modifier`, at `RegexParser.kt:3728:48`, identical on `compileKotlinJvm` and
   `compileKotlinJs`. Root grammar construct: `start=(COLLECTION_LITERAL_CHAR | DASH |
   CARET)` in the `RangeColElem` alternative of `collection_elem`, `RegexParser.g4:158`.
3. No other ANTLR warnings or errors were produced during `generateKotlinGrammarSource`
   itself (Step 5's `BUILD SUCCESSFUL` had no diagnostic output at all beyond task names).

## Files changed

- `spike/antlr-kotlin/antlr/RegexLexer.g4` (new — copy with `@members`/actions
  translated to Kotlin)
- `spike/antlr-kotlin/antlr/RegexParser.g4` (new — byte-identical copy)
- `spike/antlr-kotlin/build.gradle.kts` (modified — antlr-kotlin plugin, generation task,
  `commonMain` wiring)
- `spike/antlr-kotlin/src/commonTest/kotlin/GeneratedParserSmokeTest.kt` (new)

`git status --short` outside `spike/antlr-kotlin/` shows only the pre-existing untracked
`.omc/` directory (not created by this task) — nothing outside the spike directory was
touched, confirming the constraint was respected.

## Self-review findings

- Diff for `RegexLexer.g4` against the original matches the brief's Step 2/3 spec exactly
  (verified with `diff`) — only the `@members` block and the 8 action bodies changed,
  nothing else.
- `RegexParser.g4` verified byte-identical to `vim-engine/src/main/antlr/RegexParser.g4`
  via `diff` (no output).
- `build.gradle.kts`: I reordered the task-registration `val` to precede the `kotlin { }`
  block relative to the brief's snippet ordering, for the forward-reference reason
  explained in item 5 above. This is a mechanical Gradle-script ordering fix, not a
  deviation from the brief's intent — same plugin, same task config, same source-set
  wiring, same dependency.
- No generated/build artifacts were staged or are at risk of being committed — the root
  `.gitignore` already has `**/build/`, so `spike/antlr-kotlin/build/` is ignored even
  though the local `spike/antlr-kotlin/.gitignore` (from Task 1) doesn't separately list
  `build/`. Confirmed via `git check-ignore -v`.
- Committed as `454e55398` (`spike: generate Kotlin regex parsers via antlr-kotlin`) —
  see Concerns below for the reasoning on committing a known-red build state.

## Concerns

1. **The central spike question got a partial-negative answer.** ANTLR grammar
   generation for the regex grammars works cleanly on antlr-kotlin, but the generated
   Kotlin does not compile as-is, on both JVM and JS, due to a real generator limitation
   (grammar labels colliding with base-class member names are handled silently in Java
   but are a hard compile error in Kotlin — and unfixable by `override` since the base
   member isn't `open`). This is exactly the class of finding the task anticipated and
   asked me to surface rather than route around.

2. **This is very likely fixable without touching the grammar's semantics** — e.g.
   renaming the label from `start=` to something like `startTok=` in `RegexParser.g4`
   would almost certainly resolve it, since it's a purely cosmetic label name with no
   behavioral significance visible outside the generated context class. But the brief
   explicitly required `RegexParser.g4` to stay byte-identical and told me not to find
   workarounds, so I left it as a recorded finding rather than acting on this guess.
   **Whoever owns Phase 1 planning should decide** whether this specific collision is
   worth a one-line, semantically-inert grammar rename (cheap, surgical, arguably not
   even a "downgrade") versus treating it as a structural limitation of antlr-kotlin.

3. **I committed a known-red build state (commit `454e55398`).** The brief says to commit
   unconditionally at Step 8, and the resulting state — while it has generated grammar
   sources that leave `jvmTest`/`jsNodeTest` failing to compile — has real evidence value
   as-is, and the spike directory is isolated from the rest of the repo (root build
   untouched). I flagged the reasoning explicitly in the commit message rather than
   describing it as passing, since Step 7's "Expected: PASS on both" was not met. If the
   team lead would rather this state not be in history (e.g. because downstream tasks
   should only build on a green foundation), it's a single commit and easy to revert.

4. **API/nullability differences noticed between `org.antlr.v4.runtime` and
   `org.antlr.v4.kotlinruntime`** (for whoever ports the regex parser subpackage later):
   - Token label fields generated as `@JvmField @JsName("start$") public var start:
     Token? = null` — nullable `Token?` where the Java runtime uses a raw (nullable by
     convention only) `Token`. Callers reading these fields from ported Kotlin code will
     need null-handling (`!!` or safe calls) that the original Java call sites didn't.
   - `ParserRuleContext.start`/`.stop` are `final` properties in the Kotlin runtime (not
     `open`), unlike Java's `ParserRuleContext.start`/`.stop` fields which are plain
     public fields that can be shadowed freely by subclasses. This is the direct cause of
     the Step 7 blocker and is worth remembering for any other grammar this project might
     later try to port — any token label reusing one of `children`, `start`, `stop`,
     `exception`, `payload`, `parent` (the full member list I checked, from `javap` on
     `ParserRuleContext`) will hit the same wall.
   - I did not get far enough (compilation never succeeded) to check runtime behavior
     differences (e.g., `RuleContext` tree walking, visitor dispatch, listener callback
     signatures) between the two runtimes — that verification is blocked pending a
     resolution to the Step 7 issue.

---

## Addendum: fix applied per Ruling 4 — final status DONE

The team lead independently verified the `start=` diagnosis and ruled that the brief's
"`RegexParser.g4` stays byte-identical" constraint was wrong on this point, since Steps
2–3 already sanction editing the spike's *copy* of `RegexLexer.g4` for the same class of
reason (spike-only grammar edit to satisfy the Kotlin toolchain, main-build grammar
untouched). Full ruling text is in the team-lead message; summarized below.

### What I changed

`spike/antlr-kotlin/antlr/RegexParser.g4` line 158 — renamed the `RangeColElem`
alternative's token label from `start=` to `rangeStart=`:

```diff
 collection_elem : collection_char_class_expression                                                                 #CharClassColElem
-                | start=(COLLECTION_LITERAL_CHAR | DASH | CARET) DASH end=(COLLECTION_LITERAL_CHAR | DASH | CARET) #RangeColElem
+                | rangeStart=(COLLECTION_LITERAL_CHAR | DASH | CARET) DASH end=(COLLECTION_LITERAL_CHAR | DASH | CARET) #RangeColElem
                 | (COLLECTION_LITERAL_CHAR | DASH | CARET)                                                         #SingleColElem
```

`vim-engine/src/main/antlr/RegexParser.g4` is untouched — confirmed with
`git diff vim-engine/src/main/antlr/RegexParser.g4` (no output) both before and after
this change. No generated code was hand-edited; only the `.g4` source label changed, and
`generateKotlinGrammarSource` was rerun to regenerate everything from it.

### Commands run and output

```
$ ./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource --console=plain
> Task :cleanGenerateKotlinGrammarSource
> Task :generateKotlinGrammarSource

BUILD SUCCESSFUL in 1s
2 actionable tasks: 2 executed
```

Confirmed the generated field renamed and the collision is gone:
```
$ grep -n 'rangeStart' .../RegexParser.kt
3728:        @JvmField @JsName("rangeStart$") public var rangeStart: Token? = null
3845:                    _localctx.rangeStart = _token
3850:                    _localctx.rangeStart = _token
```

```
$ ./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest --console=plain
...
> Task :compileKotlinJs
> Task :jsMainClasses
> Task :compileTestKotlinJs
> Task :jsTestClasses
> Task :compileKotlinJvm
> Task :jvmMainClasses
> Task :jvmJar
> Task :compileTestKotlinJvm
> Task :jvmTestClasses
> Task :jvmTest
> Task :compileTestDevelopmentExecutableKotlinJs
> Task :jsTestTestDevelopmentExecutableCompileSync
> Task :jsNodeTest

BUILD SUCCESSFUL in 7s
23 actionable tasks: 13 executed, 10 up-to-date
```

Both `compileKotlinJvm` and `compileKotlinJs` now succeed, and both `jvmTest` and
`jsNodeTest` pass. Verified the smoke test actually executed (not skipped/no-source) on
both platforms via the JUnit XML reports:

```
spike/antlr-kotlin/build/test-results/jvmTest/TEST-GeneratedParserSmokeTest.xml:
  <testsuite name="GeneratedParserSmokeTest[jvm]" tests="1" skipped="0" failures="0" errors="0" .../>
  <testcase name="generated regex parser parses a literal pattern()[jvm]" .../>

spike/antlr-kotlin/build/test-results/jsNodeTest/TEST-jsNodeTest.GeneratedParserSmokeTest.xml:
  <testsuite name="jsNodeTest.GeneratedParserSmokeTest" tests="1" skipped="0" failures="0" errors="0" .../>
  <testcase name="generated regex parser parses a literal pattern[js, node]" .../>
```

This is the first confirmed evidence that antlr-kotlin-generated code for the regex
grammars runs correctly under both the JVM and Node.js — the central question this spike
was built to answer, now answered positively for these two grammars (with the caveat
below about the required Phase 1 grammar/engine change).

No further collisions or compile errors surfaced after this fix — the build went straight
from "1 error, both targets" to a clean pass with no new errors in between.

### Commit

`e26ef4f09` — `spike: fix start= label collision, both antlr-kotlin targets pass`

### Reserved-member collision list — gate evidence for auditing all three grammars

This is the full member list I checked via `javap` against
`org.antlr.v4.kotlinruntime.ParserRuleContext` (repeated here explicitly, as requested,
because it's the actual audit checklist Phase 1 needs, not just a note about the one
instance found):

```
children, start, stop, exception, payload, parent
```

**Any token label in any of the three grammars (`RegexLexer.g4`, `RegexParser.g4`,
`Vimscript.g4`) that reuses one of these six names as a label will hit the exact same
wall** — a Kotlin compile error ("hides member of supertype... needs an 'override'
modifier") that cannot be resolved with `override` because none of these base members are
declared `open` in the Kotlin runtime. This is not specific to `RegexParser.g4` or to
`start` — it's a structural incompatibility between how ANTLR's Java target treats field
hiding (silently permitted) and how Kotlin treats it (a hard error, with no escape hatch
here). Phase 1 needs to grep all three `.g4` files for labels matching this list before
attempting the Kotlin Multiplatform port, not just fix the one instance this task found.

As a one-off data point (not a full audit — `Vimscript.g4` is Task 6's territory, and I
did not review it beyond this single grep): `grep -noE '\b(children|start|stop|exception|payload|parent)=' vim-engine/src/main/antlr/Vimscript.g4`
returned no matches. That's a good sign but should not be treated as a substitute for
Task 6's own assessment — it only checks label names, not other possible collision shapes
(e.g. rule names, listener/visitor method names).

### Runtime behavior check (visitor dispatch, tree walking, listener signatures)

The original report noted I couldn't check this because compilation never succeeded. Now
that it does, I ran one throwaway, uncommitted verification test (not part of the
committed deliverable — added, run, and then deleted; `git status --short` was clean
before and after) to answer this directly rather than speculate:

- Parsed `"a\|b\c[x-y]"` (exercises alternation, an ignore-case action, and a collection
  range) through `RegexLexer`/`RegexParser`.
- Called `.accept()` on the resulting `PatternContext` with a custom
  `RegexParserBaseVisitor<Unit>` subclass overriding `visitChildren` to count visits —
  dispatch worked correctly and walked multiple rule contexts (`visited > 1`), matching
  the generated `accept()` bodies I inspected (e.g. `RegexParser.kt:521`, which mirrors
  the standard ANTLR pattern: `if (visitor is RegexParserVisitor) visitor.visitPattern(this)
  else visitor.visitChildren(this)`).
- Ran `ParseTreeWalker.DEFAULT.walk(listener, tree)` with a `ParseTreeListener`
  implementing `enterEveryRule`/`exitEveryRule` — enters and exits fired in balanced,
  matching counts (`enters == exits`, both `> 1`), confirming listener-based tree walking
  works correctly at runtime, not just at the type level.
- This passed identically on **both** `jvmTest` and `jsNodeTest` (confirmed via the JUnit
  XML reports, `tests="1" failures="0" errors="0"` on both), so visitor dispatch and tree
  walking are confirmed working under Node, not just the JVM.

**One real API difference surfaced along the way**: `RegexParserBaseVisitor<Unit>` did
not compile until I added an explicit `override fun defaultResult() {}`. Kotlin's
`AbstractParseTreeVisitor<T>` requires a concrete `defaultResult(): T` implementation
whenever `T` has no automatically-derivable default — this differs from Java's
`AbstractParseTreeVisitor<T>`, where `defaultResult()` returns `null` and needs no
override for reference types including `Void`. Anyone writing (or porting) a
`Unit`-returning visitor subclass on the Kotlin runtime needs to remember this — it's a
one-line fix, but a silent trap if you don't know to expect it, since the Java-side
convention "returning null is fine, don't override defaultResult" doesn't carry over.

No other behavioral surprises surfaced in this check. I did not exercise the full
production visitor implementations (`CollectionElementVisitor` and friends) — that's
Task 3's job once it ports them onto this runtime — so this is necessarily a narrow,
mechanics-level check of the generated scaffolding, not a claim that the eventual ported
visitors will be issue-free.

### Additional findings for Phase 1 (as requested)

- **Renaming this label is a required Phase 1 change to the real grammar**, i.e. this
  fix must also land in `vim-engine/src/main/antlr/RegexParser.g4` if/when the project
  commits to the Kotlin Multiplatform port — it isn't spike-only cosmetics.
- **It ripples into engine source.** Confirmed by reading
  `vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp/parser/visitors/CollectionElementVisitor.kt:27`:
  ```kotlin
  override fun visitRangeColElem(ctx: RegexParser.RangeColElemContext): Pair<CollectionElement, Boolean> {
    val rangeStart = cleanLiteralChar(ctx.start.text)
  ```
  This call site reads `ctx.start.text` and must become `ctx.rangeStart.text` when the
  real grammar is renamed. (Coincidentally the local variable it assigns to is already
  named `rangeStart` — only the `ctx.start` → `ctx.rangeStart` member access needs to
  change.)
- **This reveals a Java/Kotlin semantics gap worth flagging on its own**: the current
  Java code relies on field hiding of an inherited ANTLR runtime member
  (`ParserRuleContext.start`) by a grammar-generated subclass field of the same name —
  something Java permits silently and Kotlin refuses outright, with no `override` escape
  hatch since the base member isn't `open`. Any future Phase 1 grammar work (not just
  this one label) needs to treat "does a token label shadow a `ParserRuleContext` member
  name" as a real static-analysis check when writing or reviewing grammars destined for
  Kotlin Multiplatform, not just an ANTLR-visible property name.

The rest of my findings on `org.antlr.v4.runtime` vs `org.antlr.v4.kotlinruntime` API and
nullability differences (see "Concerns" section above) stand unchanged and are ready for
Task 3 to consume.

## Report file
`/Users/neshkeev/projects/ideavim/.superpowers/sdd/2026-08-16-antlr-kotlin-gate/task-2-report.md`
