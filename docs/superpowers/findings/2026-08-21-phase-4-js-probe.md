# Phase 4 probe: how much of the engine compiles for JavaScript

**2026-08-21.** Ran with `js(IR) { nodejs() }` temporarily added to `vim-engine`. The target is
not left enabled - see the end of this document.

## Result

**729 of `commonMain`'s 798 files compile for Kotlin/JS.** 69 files produce 308 errors.

The first run produced **2153** errors. One module extraction removed 1845 of them.

## The single biggest finding: `:annotation-processors` was JVM-only

`@CommandOrMotion`, `@ExCommand` and `@VimscriptFunction` are declared in
`:annotation-processors`, a `kotlin("jvm")` module - it has to be, because it depends on KSP's
`symbol-processing-api`. Every annotated file in the engine therefore could not compile for JS,
which is most of the action and command classes.

The annotations themselves are plain Kotlin with no dependencies whatsoever. Splitting them into
`:vim-annotations`, a multiplatform module, took 1845 errors to zero.

**This was invisible to the seed classifier**, which only looks for `java.*` / `javax.*` /
`org.jetbrains.annotations` imports. `com.intellij.vim.annotations.CommandOrMotion` looks like
ordinary project code. It is the third time a "JVM-only" dependency has been one the classifier
had no rule for - after `com.intellij.vim.api` (W6) and the `*.jvm.kt` actuals - and the lesson is
the same each time: **the classifier finds JVM *APIs*, not JVM *modules*.** The compiler is the
only thing that finds the latter.

## Superseded - see the update below

## What remains: 308 errors in 69 files

| unresolved reference | count |
|---|---:|
| `Character` | 85 |
| `javaClass` | 33 |
| `JvmStatic` | 14 |
| `runBlocking` | 11 |
| `JvmField` | 9 |
| `it` | 8 |
| `Runnable` | 6 |
| `System` | 6 |
| `format` | 4 |
| `removeIf` | 4 |
| `assert` | 4 |
| `Cloneable` | 4 |
| `stream` | 3 |
| `computeIfAbsent` | 3 |
| `Integer` | 3 |
| `codePointCount` | 2 |
| `codePointAt` | 2 |
| `Object` | 1 |
| `putIfAbsent` | 1 |
| `comparing` | 1 |

By concentration:

| file | errors | cause |
|---|---:|---|
| `api/VimDigraphGroupBase.kt` | 104 | `Character.UnicodeBlock` and friends - one third of all remaining errors |
| `common/Graphemes.kt` | 21 | `Character` codepoint APIs |
| `api/VimStringParserBase.kt` | 9 | `Character`, `String.format` |
| `KeyHandler.kt` | 9 | `javaClass`, `Runnable` |

Grouped by kind of work:

- **`java.lang.Character` (85 refs, plus 64 type-inference failures downstream)** - the largest
  item by far, and concentrated in two files. `Char.category` and explicit range checks cover most
  of it; the pattern is already established by `CharacterHelper` and `charCategoryOf`.
- **`javaClass` (33)** - `platformClassName` already exists as expect/actual; these are the sites
  that were not on the closure path and so were never converted.
- **`@JvmStatic` / `@JvmField` (23)** - optional expectations that are *not* available for JS in
  every position they are used here.
- **`runBlocking` (11) and 8 suspend-call errors** - `kotlinx.coroutines` has no `runBlocking` on
  JS. This needs a real answer, not a shim: the call sites assume they can block.
- **assorted JVM APIs (~40)** - `System`, `Runnable`, `Cloneable`, `Integer`, `String.format`,
  `stream`, `computeIfAbsent`, `removeIf`, `codePointAt/Count`, `assert`.
- **2 x `synchronized`** - not supported on Kotlin/JS at all.

## Why the target is not enabled

Adding `js(IR)` to `vim-engine` today would leave `./gradlew build` red. The probe's value is the
work-list above, not a broken build, so the target stays off until the 69 files are done. `:api`
and `:vim-annotations` **do** have JS targets and compile, which proves the toolchain and the
dependency chain work.

## Honest caveat

Compiling is not running. Even at zero errors this says nothing about behaviour: the design spec
already lists string and char handling, regex semantics, number coercion and collection iteration
order as expected divergences, and none of those show up as a compile error. The headless host
(phase 2) is what would let the suite answer that question, and it is still not built.


---

# Update: 171 errors in 64 files

Progress since the first probe, all of it verified against the JVM implementations being replaced:

| | errors |
|---|---:|
| first run | 2153 |
| after extracting `:vim-annotations` | 308 |
| after `DigraphUnicodeBlock` | 220 |
| after the codepoint helpers | **171** |

`Character` went from 85 references to 5.

**`DigraphUnicodeBlock`** replaces `Character.UnicodeBlock` for the `:digraphs` headers. Only 27
blocks are modelled, and that is provably enough: the listing assigns `previousUnicodeBlock` only
inside the branch that has already confirmed the block has a header name, so a block without one
can never be observed. The ranges are generated from the JDK and `DigraphUnicodeBlockTest` walks
every codepoint of every range, plus a margin either side, so a range that is too wide or too
narrow both fail.

**The codepoint helpers** - `codePointAt`, `codePointBefore`, `charCount`, `codePointCount`,
`toChars`, `isLetterCodePoint` - are hand-written surrogate arithmetic, pinned by `CodePointsTest`
against `java.lang.Character`. The test includes unpaired surrogates deliberately: the JDK returns
them as themselves rather than throwing or substituting, and a reimplementation that tidied them up
would differ only on malformed text, which is exactly where it would matter and never be noticed.

`isRightToLeft` is expect/actual, and deliberately narrower than the `Character.getDirectionality`
it replaces: the engine only ever asks whether a codepoint is RTL, and exposing a directionality
byte would oblige every host to reproduce the whole Unicode bidi table instead of the part in use.

## What remains

| unresolved reference | count |
|---|---:|
| `javaClass` | 33 |
| `JvmStatic` | 14 |
| `runBlocking` | 11 |
| `JvmField` | 9 |
| `it` | 8 |
| `Runnable` | 6 |
| `System` | 6 |
| `Character` | 5 |
| `format` | 4 |
| `removeIf` | 4 |
| `assert` | 4 |
| `Cloneable` | 4 |
| `stream` | 3 |
| `computeIfAbsent` | 3 |
| `Integer` | 3 |
| `codePointCount` | 2 |

Grouped:

- **`javaClass` (33)** - mostly `javaClass != other.javaClass` in `equals`, which becomes
  `this::class != other::class` since `KClass` is multiplatform. Two sites use `.name` and need
  `platformClassName`; one uses `getResourceAsStream` and cannot move at all.
- **`@JvmStatic` / `@JvmField` (23)** - optional expectations, unavailable for JS in the positions
  used here.
- **`runBlocking` (11) plus 8 suspend-call errors** - the one item that needs a design decision
  rather than a substitution. The call sites assume they can block; JS has no way to.
- **the tail (~50)** - `System`, `Runnable`, `Cloneable`, `String.format`, `removeIf`,
  `computeIfAbsent`, `assert`, and two `synchronized` blocks that Kotlin/JS does not support at all.


---

# Update 2: 104 errors in 48 files

| | errors |
|---|---:|
| first run | 2153 |
| after extracting `:vim-annotations` | 308 |
| after `DigraphUnicodeBlock` | 220 |
| after the codepoint helpers | 171 |
| after the `javaClass` rewrite | 133 |
| after `kotlin.jvm` imports and `serialization-core` | **104** |

**95 % of the original error count is gone.**

Two of these were not code problems at all, which is worth separating from the real work:

- **`kotlin.jvm.*` is in the JVM default imports and not the common ones.** `@JvmStatic` and
  `@JvmField` had always been written without an import, which is correct in `jvmMain` and does not
  resolve in `commonMain`. 23 errors, fixed by adding the import to 24 files. Note the trap on the
  way: `@Throws` is `kotlin.Throws`, a default import, and `kotlin.jvm.Throws` is deprecated - so
  adding the "matching" import for it *created* 9 errors before removing it fixed them.
- **`kotlinx-serialization` was only declared for `jvmMain`**, as the `-jvm` artifact.
  `serialization-core` is multiplatform and now sits in `commonMain`.

`javaClass` went to zero. Most sites were `javaClass != other.javaClass` in `equals`, which becomes
`this::class != other::class` because `KClass` is multiplatform - only `kotlin.reflect.full` is not.
Three sites needed more care because their output is user-visible: `platformCanonicalName` (`:map`
prints it) and `platformClassToString` (a command alias prints it) are expect/actual, because
canonical, binary and `toString` forms differ from each other and reconstructing one from another
would be a guess. `SmileCommand` reads an ASCII-art resource off the classpath and moved to
`jvmMain`.

## What is actually left

| unresolved reference | count |
|---|---:|
| `runBlocking` | 11 |
| `it` | 8 |
| `Runnable` | 6 |
| `System` | 6 |
| `Character` | 5 |
| `format` | 4 |
| `removeIf` | 4 |
| `assert` | 4 |
| `Cloneable` | 4 |
| `stream` | 3 |
| `computeIfAbsent` | 3 |
| `Integer` | 3 |
| `codePointCount` | 2 |
| `codePointAt` | 2 |

- **`runBlocking` (11) and 8 suspend-call errors** - still the one item that needs a decision
  rather than a substitution.
- **2 `synchronized` blocks** - Kotlin/JS does not support them at all.
- **the rest** - `System`, `Runnable`, `Cloneable`, `String.format`, `removeIf`, `computeIfAbsent`,
  `Integer`, `stream`, `assert`, and 9 deprecated `String(CharArray, ...)` constructors that want
  `concatToString`.

No file has more than 10 errors left; the largest are `VimDigraphGroupBase` (10),
`VimJumpServiceBase` (8) and `KeyHandler` (8).


---

# Update 3: 45 errors in ~20 files

| | errors |
|---|---:|
| first run | 2153 |
| after `:vim-annotations` | 308 |
| after `DigraphUnicodeBlock` | 220 |
| after the codepoint helpers | 171 |
| after `javaClass` | 133 |
| after imports and `serialization-core` | 104 |
| after the JVM-API tail | **45** |

**98 % of the original error count is gone.** What is left is no longer a tail:

- **`runBlocking` (11) and 6 suspend-call errors**, all in `thinapi/*ScopeImpl`. The extension API
  exposes suspend functions and the scopes call them from non-suspend context by blocking. JS
  cannot block. This is a design question about the extension API, not a substitution.
- **`Runnable` (6)** - engine API taking `java.lang.Runnable`; `() -> Unit` would do, but it
  changes signatures the plugin calls.
- **`Cloneable` (4)** - Kotlin/JS has no `Cloneable`. The four classes keep their `clone()`; only
  the marker interface has to go.
- **2 `synchronized` blocks in `KeyHandler`** - unsupported on Kotlin/JS. Needs the same treatment
  as `ConcurrentCollections`: an expect/actual that is a real lock on the JVM and a pass-through
  where there are no threads.
- 2 type-inference failures in `LangMapOptionHelper`.

## A near-miss worth recording

The sweep was done with regular expressions over 24 files, and two of them were wrong in ways the
JVM compiler caught only by luck:

- `assert(` was replaced blindly. That renamed two *declarations* - `NFA.assert(...)`, a regex-engine
  method, and `StrictMode.assert(...)`, IdeaVim's own helper - along with all their call sites. It
  compiled locally and broke a file in `jvmMain` that had never been touched, which is the only
  reason it was noticed.
- `String(x)` lacked a word boundary and rewrote the inside of `getSingleQuotedString(editor)` into
  `getSingleQuoted` + `editor.concatToString()`.

Both were reverted precisely rather than patched over. The lesson is narrow and practical: a
textual rewrite cannot tell a call from a declaration, and `assert`, `String`, `format` and `stream`
are all words a codebase uses for its own things.


---

# Update 4: 20 errors, all of them one problem

| | errors |
|---|---:|
| first run | 2153 |
| ... | ... |
| after the JVM-API tail | 45 |
| after `Runnable`, `Cloneable`, `withLock` | **20** |

**99 % gone, and what is left is no longer a list.** All 20 errors are in six files, all
`thinapi/*ScopeImpl`, and all the same thing:

```
thinapi/CommandScopeImpl.kt        5
thinapi/ModalInputImpl.kt          4
thinapi/ListenerScopeImpl.kt       3
thinapi/MappingScopeImpl.kt        3
thinapi/TextObjectScopeImpl.kt     3
thinapi/commandline/CommandLineScopeImpl.kt  2
```

## The one remaining question

The extension API (`:api`) declares its callbacks as `suspend` functions. The scope
implementations are called from the engine's ordinary non-suspend code, so they bridge the gap with
`kotlinx.coroutines.runBlocking`. One of them says so in a comment: *"suspend lambda bridged via
runBlocking for now"*.

`runBlocking` does not exist on Kotlin/JS, and cannot: a single-threaded event loop has no way to
block while waiting for a continuation. This is not a missing shim. Either

1. the engine paths that invoke extension callbacks become `suspend` themselves, which propagates
   up through `KeyHandler`, or
2. the extension API stops being `suspend` and the callbacks become ordinary functions, or
3. the JS host gets a different bridge - callbacks queued and resumed on the event loop - which
   changes when extension code observes editor state relative to the JVM.

Only the third preserves both the current API and the current JVM behaviour, and it is the one that
makes the two hosts behave differently. This wants a decision before code.

## Things fixed in this pass

- `Runnable` to `() -> Unit` across the engine API. The IntelliJ implementations still hand a
  `Runnable` to the platform; Kotlin SAM-converts at that boundary. `KeyHandler` passed the same
  `ActionRunner` instance as both the work and the command group id, so the replacement passes
  `action::run` and `action` to keep that identity.
- `Cloneable` removed from four classes. It is a marker on the JVM and their `clone()` methods are
  hand-written copies, not `Object.clone()`, so nothing is lost.
- `synchronized` became `withLock`, an **inline** expect/actual. Inline because the call sites wrap
  whole method bodies and return from the middle of them - a non-inline wrapper would have needed
  four `return@withLock` rewrites inside a 75-line method.
- `String(IntArray, offset, count)` is the *codepoint* constructor, not the `CharArray` one. The
  compiler caught that by argument type after the deprecation warning pointed at the wrong fix.


---

# The runBlocking decision, with the evidence

All 20 remaining errors are `runBlocking` in seven call sites across six files. They are not
interchangeable, and the difference decides which options are actually available.

## The seven sites

| site | the suspend callback returns | can it be deferred? |
|---|---|---|
| `CommandScopeImpl.exportOperatorFunction` | `Boolean` | **no** |
| `TextObjectScopeImpl` range provider | `TextObjectRange?` | **no** |
| `CommandScopeImpl.register` | `Unit` | yes |
| `ListenerScopeImpl` | `Unit` | yes |
| `MappingScopeImpl` | `Unit` | yes |
| `ModalInputImpl.inputString` / `inputChar` | `Unit` | yes |
| `CommandLineScopeImpl.input` | `Unit` | yes |

The engine calls these through its own **synchronous** interfaces. `OperatorFunction.apply` returns
a `Boolean` the engine needs before it can decide what to do next; the text-object range provider
returns the range the motion is about to use. Neither can be answered later.

**That rules out the "queue it on the event loop" option**, which the first write-up listed as the
one preserving both the API and JVM behaviour. It does not: it cannot serve two of the seven sites
at all. Correcting that here rather than leaving it to be discovered during implementation.

## So the real choice is two

1. **Make the engine's own interfaces suspend.** `OperatorFunction.apply`, `CommandAliasHandler.execute`
   and the text-object provider become `suspend`, and that propagates up to whatever calls them -
   ultimately `KeyHandler`. Largest change, no behavioural difference between hosts, and it is the
   only option where the JVM keeps working exactly as it does now.
2. **Drop `suspend` from the extension API.** Smallest change to the engine, but it is a public API
   that extensions are written against, and it removes the ability for an extension callback to
   suspend at all.

A hybrid is available and probably wrong: defer the five `Unit` sites and make only the two
value-returning paths suspend. It would leave the API half-suspending with no rule a user could
predict.

## Not a new discovery

`MappingScopeImpl` line 279 already says:

> `// XXX: It's not OK to call runBlocking, but let's keep it to have an API.`

and `TextObjectScopeImpl` says "bridged via runBlocking for now". The port did not create this
problem; it removed every other reason not to deal with it, and made the JVM's tolerance of
`runBlocking` the only thing still hiding it.


---

# Option 1 measured, and a reason to reconsider it

Option 1 - make the engine's own interfaces `suspend` - was attempted end to end and then reverted.
The code is gone; the measurement is the point.

## What it costs

| | |
|---|---:|
| files touched | **447** |
| `suspend` markers added | **672** |
| files still needing individual judgement when it stalled | 17 |
| **new `runBlocking` calls on the JVM host** | **11 and rising** |

Most of it is mechanical and was applied by script: the compiler names every override that needs
`suspend`, and three small scripts (add to override, add to base declaration, add to enclosing
function) took it from 224 errors to 26 in a few rounds. That part works.

It stalls on lambdas passed to non-inline higher-order functions - `MappingProcessor`,
`EditorActionHandlerBase.process`, the key consumers, `ReduceFunctionHandler`. Each needs a
decision about whether that particular callback should be suspend, and several are on the
key-handling hot path.

## The finding that matters

**Option 1 does not remove blocking. It moves it into the host and multiplies it.**

The chain terminates wherever the engine meets an IntelliJ callback API, because those take Java
functional interfaces that a suspend lambda cannot cross. The clearest case is caret iteration:

```kotlin
override suspend fun forEachCaret(action: suspend (VimCaret) -> Unit) {
  editor.caretModel.runForEachCaret({ runBlocking { action(IjVimCaret(it)) } }, false)
}
```

`runForEachCaret` is IntelliJ's, takes a `CaretAction`, and has no suspend form. So the JVM host
ends up calling `runBlocking` **inside the caret loop, on the EDT, once per caret**, for every
multi-caret action. Before this change the JVM had seven `runBlocking` calls, all in `thinapi`.
After it, the engine has none and the host has eleven, with more to come from the sites that had
not been reached yet.

That is not obviously wrong - blocking genuinely is a host concern, and a JVM host is allowed to
block - but it is a different trade than "largest change, no behavioural difference between hosts".
There is a plausible performance and deadlock-risk regression on the JVM that was not part of the
decision as it was put.

It works today only because nothing in the engine actually suspends: the markers are structural, so
every coroutine completes without yielding. The first engine path that really suspends would
deadlock on the EDT.

## What this suggests

Option 2 - dropping `suspend` from the extension API - now looks better than it did, because option
1's benefit is narrower than advertised: it makes the *engine* neutral while making the *JVM host*
block more often and in worse places. Option 2 removes the blocking from both.

The counter-argument stands: option 2 removes the ability for an extension callback to suspend at
all, and it is a published API. But that is a smaller and more honest cost than seven `runBlocking`
calls becoming eleven-plus, several of them per-caret on the EDT.


---

# Done: the engine compiles for JavaScript

Option 2 - dropping `suspend` from the extension API - took **zero** JS errors, from 20.

| | |
|---|---:|
| `suspend` markers removed from `:api` | 109 |
| `suspend` markers removed from `thinapi` implementations | 90 |
| plugin extension helpers de-suspended | 17 across 10 files |
| `runBlocking` calls left **in the engine** | **0** |
| `runBlocking` calls added to the host | **0** |
| JS compile errors | **0** |

Compare with option 1, measured a day earlier: 447 files, 672 `suspend` markers, 11 new
`runBlocking` calls on the JVM host and rising, and it did not finish. Option 2 is smaller than
option 1 by more than an order of magnitude and removes the blocking rather than relocating it.

`./gradlew :vim-engine:jsNodeProductionLibraryDistribution` now produces a JavaScript library.

## What the JS actuals do, and what they refuse to do

Thirteen `expect` declarations needed a JS side. Nine are real implementations:

- **`concurrentCollectionOf`** is copy-on-write. JS has no threads, but the *second* requirement in
  the `expect` still applies - a listener may remove itself while being notified - so iteration
  walks a snapshot, which is the property `ConcurrentLinkedDeque` provided.
- **`enumSetFrom`** iterates in **ordinal order**, not insertion order, because that is what
  `EnumSet` does on the JVM and mapping listings are rendered to the user in iteration order.
- **`withLock`** runs the block directly. **`vimAssert`** is a no-op, matching a JVM without `-ea`.
- **`nanoTime`** is `performance.now()` scaled to nanoseconds; **`currentTimeMillis`** is `Date.now()`.
- The eight annotations are inert.

**Four are `TODO()` on purpose**, and that is the honest state rather than a gap I missed:

| stub | why not guessed |
|---|---|
| `charCategoryOf` beyond the basic plane | no Kotlin/JS way to get a supplementary codepoint's category; a plausible default would make `:digraphs`, grapheme iteration and word motions silently wrong |
| `isRightToLeft` | JS has no bidi table |
| `lookupEngineMessage` | the bundle is MessageFormat, including `{0,number,#0}` and twelve lines whose quotes are escapes; a naive substitution corrupts them rather than failing |
| `formatVimFloat` | `FloatFormatTest` holds 46 rows this has to reproduce exactly; JS `toFixed` rounds half-away-from-zero on the decimal string and does not satisfy them |

Each of those has a known solution - an embedded Unicode table or `RegExp` property escapes, an
embedded bundle plus a MessageFormat-compatible formatter, and a decimal formatter that satisfies
the golden table. None of them is guesswork, and a wrong answer in any of them would be invisible
until a user noticed the wrong output.

## `platformClassName` is the one behavioural difference

Kotlin/JS can only offer `simpleName`, so action ids derived from a **nested** class will differ
from the JVM's - `VimMatchitAction` where the JVM gives `VimMatchit$MatchitAction`. The `expect`
already warned about this. A JS host that needs ids matching a JVM one has to declare them rather
than derive them.

## The JVM is untouched

12687 tests, 0 failures. The plugin zip builds.


---

# The engine runs on JavaScript

`commonTest` and `jsTest` are wired, and `PlatformContractsTest` - 17 assertions about behaviour
that must hold everywhere - passes on **both** the JVM and Node.

That is a different claim from the one above it. Compiling proved the code had no JVM dependencies;
this proves the JS `actual`s do what the JVM ones do.

## It immediately earned its keep, twice

**A real divergence.** The listener collection test asserted that iteration walks the snapshot it
started with. That passed on JS and *failed on the JVM*: `ConcurrentLinkedDeque` is weakly
consistent and showed an element added mid-iteration, where the copy-on-write JS actual never will.

Neither is wrong - the `expect` had said "safe mutation during iteration" without saying whether an
addition is *observed*. The test now asserts only what is actually guaranteed (a removal must not
truncate the walk), and the `expect` records the difference explicitly, with the warning that a
listener registering another listener must not assume the new one is notified in the same round.
That divergence was invisible to the compiler and to 12689 JVM tests.

**A silently missing target.** The first full gate reported 12706 tests, **0 of them JS**. The
tests existed and passed; `./gradlew test` simply did not run them, because `test` matches by task
name and `jsNodeTest` is not called `test` - the same trap phase 1 hit when KMP renamed `test` to
`jvmTest` and 530 tests vanished from a green build. The alias now depends on both.

A target whose tests are not in the gate is a target whose tests rot. The gate assertion now checks
the JS count specifically, not just the total.

## Where this leaves phase 4

| | |
|---|---|
| `commonMain` compiles for JS | yes |
| a JS library artifact builds | yes |
| shared contracts verified on Node | 17 assertions |
| JS tests in the standard gate | yes |
| remaining `TODO()` stubs | 2 |

The two stubs - the message bundle and float formatting - are now *writable*, because there is
somewhere to verify them. `FloatFormatTest`'s 46 golden rows can move to `commonTest` and be run on
Node the moment the JS implementation exists.


---

# Float formatting on JS, and what the golden table taught

`formatVimFloat` is implemented for JS and the 56-row table passes identically on the JVM and Node.
One stub remains, the message bundle.

It took four wrong implementations, and each was wrong in a way only the table could show.

| attempt | what it got wrong |
|---|---|
| `toFixed(6)` | rounds half-up. `0.0078125` must be `0.007812`, not `0.007813` |
| exact digits, mantissa via `Double` | re-deriving the mantissa as a `Double` destroys the exactness it depends on: `1000000.5` is exactly representable, so its mantissa is exactly `1.0000005` - a tie - but the nearest `Double` to `1.0000005` is not |
| shortest digits, half-even at every `5` | `1.0000005` is really `1.00000050000000006989`, above the midpoint, and rounds **up** |
| shortest digits, round up at every inexact `5` | `1.2345665` is really `1.2345664999999999`, below the midpoint, and rounds **down** |

The rule `DecimalFormat` actually follows, arrived at by probing it rather than reading it:

> Format the **shortest decimal that identifies the double**, and at a trailing `5`, round by which
> side of the midpoint the true value falls on. Only an exact midpoint gets half-even.

That is three-way, not two-way, and every one of those attempts is defensible until the table says
otherwise. Half of them pass all 46 of the original rows; it was the tie rows added for this - `k/128`
values, which land on seven-digit decimals ending in 5 - that separated them.

## The one thing that cannot be reconciled

`Double.MIN_VALUE` prints as `4.9e-324` on the JVM and `5.0e-324` on JS. `DecimalFormat` formats the
shortest decimal identifying a double, and **the two runtimes disagree about what that is**: Java's
`Double.toString` gives `4.9E-324`, JS gives `5e-324`. Both round-trip to the same value, so neither
is wrong and no implementation satisfies both.

It is out of the shared table and asserted portably instead - some scientific form of the smallest
subnormal - with the reason in the test. Pretending to a guarantee that cannot hold would be worse
than recording that it does not.


---

# All four stubs implemented

`jsMain` has no `TODO()` left. The engine compiles for JavaScript, produces a library, and 27
assertions run on Node as part of the ordinary `./gradlew test`.

The bundle is emitted as a Kotlin map by `generateJsMessageBundle`, from the same `.properties`
file the JVM reads, so the two hosts cannot drift. The formatter implements the three MessageFormat
features the bundle actually uses - `{n}`, `{n,number,..}`, and quoting - and the quoting is the
part that matters.

## Two pre-existing bugs in the bundle, now pinned by tests

Neither was introduced by the port. Both are asserted as current behaviour so that fixing them is a
deliberate, visible change rather than something that silently differs between hosts.

**`E354` never shows the register name.** Its pattern is `Invalid register name: '{0}'`, and in
MessageFormat a single quote opens a quoted section - so `'{0}'` is the literal text `{0}`. The user
sees `E354: Invalid register name: {0}`.

**`E146` shows a doubled quote.** Its text is `Regular expressions can''t be delimited by letters`,
written with the MessageFormat escape, but E146 takes no parameters and the no-argument path returns
the pattern verbatim without ever running MessageFormat over it. The user sees `can''t`.

Four messages in the bundle contain `''`; three of them take `{0}` and are fine. E146 is the one
that does not.

## And one more silently-passing build

`compileKotlinJs` was wired to the *generated directory* rather than to the generating *task*. It
compiled perfectly until `clean`, at which point the bundle did not exist yet. Passing the task
provider to `kotlin.srcDir` is what makes Gradle order them.

That is the third variant of the same failure in this project - after `test` matching by name and
missing `jvmTest`, then missing `jsNodeTest`. All three shared a shape: **the build was green
because it was not doing the work, not because the work succeeded.** Only `clean` plus a full gate
finds them.
