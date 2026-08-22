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

---

# The command registry: 468 handlers reachable on JS without a class loader

The three providers - commands, ex-commands, vimscript functions - were the last big block of
JVM-bound engine code that was not the parser. All three worked the same way: read a JSON resource
written by the annotation processor, then turn each class *name* into a constructor through the
class loader. Neither half survives on JS.

## Splitting the interface from the way it is filled

The provider interfaces now say only what they produce:

```kotlin
interface CommandProvider {
  fun getCommands(): Collection<LazyVimCommand>
}
```

The JSON-and-class-loader implementation moved down into `JsonCommandProvider`, `JsonExCommandProvider`
and `JsonVimscriptFunctionProvider`, which stay in `jvmMain`. The six existing implementors - three
in the engine, three in the IntelliJ plugin - name the `Json` interface instead. `RegisterActions.registerCommandProvider`
and its two siblings still take the plain interface, so a caller registering a provider is unaffected.

That move alone took `VimScriptFunctionServiceBase` - the whole builtin-function service - to
`commonMain`, because the provider type was the only thing keeping it on the JVM.

## Generating the registry the KDoc already described

`reflectiveFactory` had carried this note since the seam was cut: *"A host without a class loader
supplies its factories directly instead, from a generated registry; that is the seam this exists to
create."* `generateJsCommandRegistry` is that registry. It reads the same `engine_commands.json` and
`engine_vimscript_functions.json` the JVM reads at runtime and emits Kotlin:

```kotlin
entry(listOf("iw"), "XO", "com.maddyhome.idea.vim.action.motion.object.MotionInnerWordAction") {
  com.maddyhome.idea.vim.action.motion.`object`.MotionInnerWordAction()
},
```

375 commands and 93 functions. One JSON file, read by both targets, so the two cannot drift: there
is no second list to keep in step.

The key sequences stay as strings and are parsed by `getCommands()` exactly as the JVM parses them,
and the mode characters go through the same `MappingMode.parseModeChar`. Both targets run the same
construction code over the same input; only the factory differs.

**A missing or misspelled class is now a JS compile error**, where on the JVM it is a
`ClassNotFoundException` the first time that key is pressed.

## Two things the generator got wrong, both caught by the compiler

**`object` is a Kotlin keyword and a package name here.** The text objects live in
`com.maddyhome.idea.vim.action.motion.object`, which has to be written `` `object` `` in Kotlin.

**`InsertRegisterAction` exists twice** - `action.change.insert` for `<C-R>` in insert mode and
`action.ex` for `<C-R>` on the command line. Emitting simple names with imports was ambiguous.

Fully qualified, keyword-escaped constructor calls fix both at once, and need no imports at all.

## What the test found: 12 handlers read the injector while constructing

The obvious test - construct all 375 - fails. Twelve handlers parse their own key sequences in a
property initializer:

```kotlin
private val keySet = parseKeysSet("<C-Down>")
```

which reaches `injector.parser`. This is not a defect and not new: `LazyInstance` defers
construction to first use, so the engine never builds a handler before the injector exists, on
either target. `getCommands()` needs the injector for exactly the same reason.

So the test counts `UninitializedPropertyAccessException` separately and fails only on any *other*
error - and asserts that the injector-dependent handlers stay a small minority, since a list where
most of them needed it could no longer be built early.

**32 assertions now run on Node**, up from 27.

## `sort()` and `uniq()` came along

`SortUniqFunctionHandlers` was the only thing left keeping a handler class out of `commonMain`, and
it was two JVM APIs:

- `String.CASE_INSENSITIVE_ORDER` → `compareTo(ignoreCase = true)`, which is the same algorithm and
  is literally the same comparator on the JVM.
- `Comparator.naturalOrder()` → Kotlin's `naturalOrder()`.
- `java.text.Collator.getInstance()` → `expect fun localeCollator()`, with `Intl.Collator` on JS.

**The collator is the one place where the two targets are not promised to agree.** The JDK collates
with its own copy of CLDR and a JS runtime with whatever ICU it was built against. Both implement
the Unicode collation contract and both order accents, case and digits the way users expect, but
pinning them to each other would mean shipping our own tables. `sort(list, 'l')` is locale-sensitive
by definition, so it is not a fixed order on either target anyway. Documented at the declaration.

Clearing it meant the generator needs no exclusion list: all 468 classes it names are in
`commonMain`.

## Ex-commands are deliberately not done

`lazyExCommand` also has to decide whether each class has a `(Range, CommandModifier, String)`
constructor - `LazyExCommandInstance` carries a factory or null. A generator cannot read that off the
JSON, and inventing a second way to answer it would be a source of truth that can disagree with the
reflective one.

`ExCommandConstructorInvariantsTest` already established the invariant that makes it answerable:
the matching three-argument constructor is always the *primary* constructor. But ex-commands are
useless on JS until the vimscript parser runs there, which is W1 - so this is left to be settled in
the same unit that makes it matter, from the JVM's own answer rather than a second implementation
of it.

---

# What is left is the parser, not the JDK

Four more seeds cleared, and the interesting result is not the count - it is what happened when the
files were then moved to `commonMain` to see whether they could go.

`VimSearchGroupBase` (1,640 lines) and `VimChangeGroupBase` (2,283 lines) both now have **no JVM API
dependency at all**, and both still fail to move. Every error is `VimRegex` or `CharPointer`. They
are no longer JDK-blocked; they are W1-blocked. That is now the shape of the whole remaining set.

## `<C-A>` was three different problems wearing one import

`java.math.BigInteger` appeared at three call sites in the increment/decrement path, and they do not
want the same replacement.

**Hex and octal are 64-bit unsigned and wrap.** `ChangeNumberDecActionTest` pins `0x0000` decremented
to `0xffffffffffffffff`. `ULong` wraps on its own, so `parseUnsignedWrapping` plus
`count.toLong().toULong()` reproduces it and the explicit "if it went negative, add 2^64" correction
disappears. One deliberate difference: `BigInteger` let `0xffffffffffffffff` incremented grow a
seventeenth hex digit, and this wraps to zero, which is what Vim does.

**Decimal is arbitrary precision, and narrowing it would lose data.** Vim's own `<C-A>` is 64-bit,
but this has always incremented a number of any length correctly, and someone incrementing a long
identifier would silently get a wrapped value. So decimal got schoolbook digit-string addition,
preserving the old behaviour exactly rather than moving toward Vim here.

Hand-written carry and borrow is exactly the code that looks right and is wrong at one carry, so it
is not trusted: `NumbersDifferentialTest` runs **30,000 randomised comparisons against
`BigInteger`**, over values up to 25 digits, with `Int.MIN_VALUE`, `Int.MAX_VALUE`, `-0`, and leading
zeros among the deltas. `NumbersTest` pins a handful of the same cases on both targets.

## The printability check was measured, not guessed

`isPrintableChar` used `Character.UnicodeBlock.of(c) != null`, which no runtime outside the JVM has.
The obvious substitute is `CharCategory.UNASSIGNED`, and the obvious substitute is wrong: running
both across the whole BMP, **1,427 code points differ**, every one of them a character the JVM calls
printable and the category test does not. They are code points inside a defined block that Unicode
has not assigned.

Too many to change on a hunch, so this became `expect`/`actual` with the JVM keeping its previous
implementation verbatim and the JS side documenting what it decides differently. None of the 1,427
can arrive as a `keyChar` from a real keystroke, which is the only thing the single call site - Select
mode deciding whether a key should replace the selection - ever asks about.

This is the same shape as `localeCollator`: where the platform *is* the data, an `expect` that names
the divergence is the honest answer, and a common implementation that quietly picks one is not.

## Search offsets: a locale-dependent parse that was never meant to be

`/pattern/e+3` offsets were read with `NumberFormat.getIntegerInstance()`, which is locale-aware: in
an English locale `/pattern/e+1,5` read as an offset of 15, because the comma is a grouping
separator. Vim reads these with `atoi` and stops at the comma, giving 1.

`parseIntPrefix` is deliberately narrower than what it replaced, and matching Vim is the point. The
old behaviour was not even stable - it changed with the IDE's locale. It still rejects a leading
`+`, which the JDK also did, because callers strip the `+` themselves and pass the index after it.

It returns where it stopped as well as the value, because the caller uses that position to find the
`;` that chains a second search - `pp.index` was read 60 lines further down, which is easy to miss.

## Two moves that did land

`IdeaPlug` needed a concurrent *set* rather than the concurrent collection already in place, since
enabling an extension twice must leave one entry. `concurrentSetOf` keeps `ConcurrentHashMap` on the
JVM. Iteration order was already unspecified there and stays unspecified.

`VimVariableServiceBase` (453 lines) used `kotlin.reflect.full.createType` twice, both
`keyArgumentType != String::class.createType()`. `typeOf<String>()` is a compiler intrinsic
available on every target - but only equivalent if the two are genuinely equal, including that
neither is nullable, which is the entire point of the check. `StringKTypeEquivalenceTest` asserts
both halves rather than assuming them.

**37 assertions now run on Node**, up from 32.

---

# W1: one parser, both targets

The ANTLR Java tool is gone. `antlr-kotlin` compiles the three grammars into `commonMain`, so the
JVM and JS run the *same* generated parser rather than two that have to be kept saying the same
thing - and IdeaVim's 12,700 IntelliJ tests now exercise the exact artifact JS will use.

Phase 0's caveat 1 offered three grammar-sharing policies. (c) - migrate outright, drop the Java
copy - turned out to be nearly free: `Vimscript.g4` needed **no edits at all**, and `RegexLexer.g4`
needed four lines of `@members` translated. Its eight inline actions needed none, because
`{ setIgnoreCase(); }` is valid Kotlin too. The only real find was that `setIgnoreCase` collides
with the generated setter for `var ignoreCase`, hence `markIgnoreCase`. There is no second grammar
to keep in sync, ever.

## `defaultResult()` is a fold seed, not a "should never happen" hook

This cost four rounds of failures and is the most useful thing in this section.

`AbstractParseTreeVisitor.visitChildren` starts from `defaultResult()` and combines each child with
`aggregateResult`, whose default returns the *last* child's result. So every rule without an
explicit override is walked by that fold. Java's `defaultResult()` returned null and the pass-through
worked. The Kotlin runtime makes it abstract, and a non-null type parameter leaves nothing to
return - which makes throwing look like the obvious answer.

It isn't. It breaks the mechanism the visitor pattern is built on:

| visitor | overrides | of base methods |
|---|---:|---:|
| ScriptVisitor | 1 | 140 |
| ExecutableVisitor | 9 | 140 |
| CommandVisitor | 15 | 140 |
| ExpressionVisitor | 32 | 140 |
| PatternVisitor | 73 | 114 |

Wrapper rules - `atom`, `collec`, `char_class`, `lambda_expression` - have no override *by design*;
they exist to delegate to their single child. `ExpressionVisitor.visitLambdaExpression` calls
`super.visitLambdaExpression(ctx)`, which is the pass-through invoked by hand.

Phase 0's spike asserted the opposite - "every alternative has an override" - and made
`MultiVisitor` and `CollectionElementVisitor` throw. That assertion shipped into this port and was
reproduced three more times before the evidence arrived: **199 engine-test failures**, then **73
more** in the IntelliJ suite. This is caveat 6 landing exactly as written: the spike's 63/64 was
parse-tree construction only, and nothing downstream had ever been executed.

The faithful translation is what Java had - nullable visitors with `defaultResult() = null`. Where a
value is genuinely required the assertion goes at the point of use, not at the seed:
`ExpressionVisitor.visitExpression()` is one named helper carrying one message with the offending
text in it, instead of forty `!!`.

One piece of evidence beat all of the reasoning: `ScriptVisitor` reads `ExecutableVisitor` results
with `mapNotNull`. That is a proof that null is in the contract, and it was sitting in the code the
whole time.

## Caveat 3 was smaller than budgeted; caveat 4 is fixed rather than accepted

197 compile errors, not "plausibly hundreds of signature edits" times some multiplier. Rewriting
`org.antlr.v4.runtime` to `org.antlr.v4.kotlinruntime` took it to 148. Six files phase 0 had already
ported were still byte-identical in the repo, so they were restored verbatim: 111. The rest was `!!`
at grammar-guaranteed accessors, a few smart-cast locals, and one signature that went the *other*
way - `syntaxError` takes a non-null `Recognizer` and `msg` in the Kotlin runtime.

Caveat 4 - antlr-kotlin's `StringCharStream` reporting EOF when a surrogate pair is the last
codepoint, so any Vim pattern ending in an emoji fails to lex - would have been a regression against
today's JVM behaviour, not a limitation to accept. `VimCharStream` indexes by codepoint, lives in
`commonMain` so both targets get it, and the phase-0 test that pinned the defect now passes.

## The classifier was wrong a fourth time

17 files were reported here as having "no JDK dependency left". They had 79. The instrument reads
`import` lines, and `java.lang.Character`, `Math`, `Integer`, `StringBuffer` and `System` need no
import. **The rule is now: the only classifier that counts is compiling for JS.**

Two of those 79 were traps:

- `Character.isWhitespace` must not become `Char.isWhitespace()`, which is broader
  (`isWhitespace() || isSpaceChar()`) and would move where `w` and `b` stop.
- `Character.isSpaceChar` is a *third* question again - category-based, so it accepts a non-breaking
  space and rejects tab and newline. Both appear within a few lines of each other in the word-motion
  code.

**Caveat 5 is closed properly.** `isJavaIdentifierPart` backs `\i` and `[:ident:]`, and the spike's
`isLetterOrDigit() || '_' || '$'` approximation changed which characters match. `isIdentifierPart`
implements the JDK's actual rule - a letter, six categories, or an ignorable control - and was
checked against `Character.isJavaIdentifierPart` across all 65,536 BMP code points: zero mismatches.

## Where this leaves the engine

`jvmMain` is 28 files, from 51. Eleven of those are `.jvm.kt` actuals that belong there. `commonMain`
is 836.

Ex-commands are the one deliberate hole: `LazyExCommandInstance` needs to know whether each class has
a `(Range, CommandModifier, String)` constructor, which is not in the JSON, and deciding it from
source would be a second answer that can disagree with the reflective one. 68 of the 86 classes have
it; the 18 that do not are exactly the ones `CommandVisitor` special-cases. `engineExCommandProvider`
is an `expect val` that is empty on JS until that is generated from the JVM's own answer.

---

# Ex-commands: the answer belongs where the types are

The hole left by W1 was that `LazyExCommandInstance` needs to know whether each command class takes
`(Range, CommandModifier, String)`, and that is not in the JSON. Deriving it in the generator would
have been a second answer that can disagree with the reflective one, so it was deferred rather than
guessed.

The fix is to compute it once, in the annotation processor, which is the only place that has the
declared types. `standardConstructor` is now a field on each entry:

```json
"g[lobal]": { "class": "...GlobalCommand", "standardConstructor": false }
```

The JVM reader and the JS generator both consume it. The JVM still needs reflection to *find* the
constructor, but no longer to decide whether one exists.

Two independent checks:

- The processor's verdict - 68 classes standard, 18 not - matches the hand-written source parse used
  to scope this work: same counts, same 18 names.
- `ExCommandConstructorInvariantsTest` now compares the flag against runtime reflection for every
  registered command, so KSP's view of the declared types and the JVM's view of the loaded class
  cannot drift apart silently.

**JS has 147 of the 149 ex-commands.** `smile` reads an ASCII-art classpath resource and `so[urce]`
reads a file, so both classes are still JVM-only; they are a named exclusion in the build with the
reason recorded, and a jsTest pins them so the list shrinking reads as progress and the list growing
reads as a regression.

Two things the test caught that assumption had wrong:

**The keys are Vim's abbreviation syntax** - `d[elete]`, `s[ubstitute]`, `g[lobal]` - because that is
what the parser matches a typed command name against.

**Only 83 of 147 names carry a factory**, which looks wrong until aliases explain it: one
`MapCommand` answers to `map`, `nmap`, `vmap` and more, so the 18 non-standard classes cover
disproportionately many names. The assertion is now a majority check plus named cases on both sides
of the split, rather than a floor picked to pass.

---

# Caveat 7: the corpus runs on Node, and agrees

1,865 Vimscript commands, extracted from IdeaVim's own test suite, parsed on both targets and
compared tree-for-tree against what the **Java** ANTLR parser produced. **Zero divergences.**

This is the first evidence about JS behaviour that is not structural. Everything before it rested on
"both targets compile the same source"; this runs the parser on real input and checks the output.
And since the Java toolchain was deleted with W1, `corpus/vimscript-golden.txt` is the only
surviving record of the old parser's behaviour - it is the only thing that can still catch the
migration having changed how Vimscript parses.

The two targets share the generated parser source, so this is not looking for grammar divergence.
It is looking underneath: the ANTLR runtime's platform code, character handling, and the ATN
interpreter's arithmetic - the places Kotlin/JS and Kotlin/JVM can still differ.

## The format problem is gone rather than checked

Caveat 7(i) noted that the tab-delimited golden file breaks if any input ever contains a literal tab
- zero today, so latent rather than live. Rather than add a validator, the format is no longer
parsed at test time: `generateVimscriptCorpus` emits separate Kotlin string literals, so there is no
delimiter to break. The generator itself fails the build, naming the offending line, if a tab ever
appears.

Emitting Kotlin is also what lets the test run on both targets at all - `commonTest` has no resource
loading.

## The first performance signal for JS

The initial Node run failed on Mocha's 2-second default timeout, not on a divergence. Raised to 60s,
and the number underneath is worth recording:

| | 1,865 parses |
|---|---|
| JVM | ~1.4s |
| Node | ~5.3s |

One sample each, and JVM warmup amortises differently across a suite, so read it as "same order of
magnitude, roughly 3-4x" rather than a ratio. It matters because Vim parses patterns on every
keystroke during incremental search, so this is the number a VS Code host will be living with.
Nothing here is a blocker; it is a baseline to measure against when there is a host to measure.

---

# All 149 ex-commands, and what "remaining" actually means

`:smile` and `:source` were the last two commands JS could not reach, and neither needed the
filesystem abstraction they seemed to.

`:smile` was never file IO - it reads four fixed `.txt` files off the classpath, which is the message
bundle's shape. `generateAsciiArt` emits them as a Kotlin map; the `.txt` files stay the source of
truth and both targets print identical art.

`:source` already delegated the reading to `injector.vimscriptExecutor.executeFile`. Its only
remaining tie was `VimRcService.isIdeaVimRcFile(file)`, and that is a **host** question rather than
an engine one: where the vimrc lives is a host convention - `$HOME`, XDG, or an IDE setting - and
comparing two paths for the same file is a filesystem question, not a string one. It is now
`VimrcFileState.isVimRcFile(path)` on the injector, with the IntelliJ implementation delegating to
the service that knows the whole search order. That is what makes `:source ~/.ideavimrc` and
`:source $HOME/.ideavimrc` both count as sourcing the vimrc.

The exclusion list in the generator is gone, and the test was flipped rather than deleted: it now
asserts both commands *are* present, so reintroducing a host dependency in either fails.

## No commonMain file depends on any jvmMain file

Checked directly rather than assumed. The only two hits are a KDoc link and a commented-out line -
no code dependency at all. The 26 files left in `jvmMain` are four terminal categories:

| | count | |
|---|---:|---|
| `.jvm.kt` actuals | 12 | `expect`/`actual` pairs, by design |
| Registry implementations | 8 | JSON and the class loader; JS has its generated twin |
| JVM adapters | 2 | `AwtKeyStrokes`, `JavaPath` - bridges to AWT and `java.nio` |
| Host code | 4 | `VimRcService`, `VimPathExpansionImpl`, the two extension loaders |

**`LazyVimExtension` and `LazyExtensionFunctionInstance` do not need porting.** They were listed here
as pending; they are referenced only from `src/main`'s `IjExtensionLoader`, and the engine's own
`ideavim_extensions.json` is empty - extensions come from the plugin and from third parties.
`ExtensionLoader` in `commonMain` is already the seam, and a VS Code host would load JS modules
rather than resolve class names.

That is the second time in this phase that "pending work" turned out to be already seamed, after
`VimRcService`. Both times the mistake was the same: reading the *file's* dependencies instead of
asking who depends on the file. **Before porting a file, check who needs it - some of them are host
code that a second host simply replaces.**

The engine's common code is self-contained. The open question is no longer what else must move; it
is phase 2 - a headless host, so the suite can run against the engine without IntelliJ and "compiles
for JS" can become "verified on JS".

---

# Caveat 6, the other half: the regex matcher runs on JS

Phase 0 called `PatternVisitor` and the NFA matcher "the largest untested surface this gate leaves
behind" - both were pruned from the spike and never executed. W1 ported them. This runs them:
**247 tests, on both targets, zero failures.** `jsNodeTest` goes from 39 to 286.

The blocker was Mockito, which pinned `VimRegexTestUtils` to the JVM. Replacing it with hand-written
fakes is a strict improvement rather than a workaround:

**The fakes are stricter than the mocks were.** Mockito silently returns null or zero for anything
unstubbed, so a test that started depending on a new editor member would quietly match against a
default. `TestVimEditor` implements the 8 members the regex engine actually asks for and `TODO`s the
other 55, each naming itself.

**The compiler wrote the stub list.** Write the real members, compile, and Kotlin prints every
missing signature; a script turned those into stubs. 55 for the editor, 24 for the caret.

**One member needed judgement rather than a stub.** `LocalMarkStorage.getMark` asks
`injector.markService` when the caret is primary and reads its own map otherwise. There is no
injector in a common test, so `TestVimCaret.isPrimary = false` is what makes local marks work - and
marks are not incidental here, they are what `\%'m`, `\%<'m` and `\%>'m` compare against.

Two portability bugs surfaced on the way: `StringBuilder.delete` is a JVM-only member, and the
JUnit `@Test` import had to become `kotlin.test.Test`.

What Node now runs is real Vim regex behaviour - collections, back-references, lookaround,
quantifiers, marks, case sensitivity - not just construction.

## The remaining jvmTest files, and which of them should move

Several exist specifically to pin the multiplatform helpers written for this port -
`CodePointsTest`, `CharacterHelperTest`, `RightToLeftTest`, `JdkCollectionShimsTest` - and currently
test only one of the two actuals, which is an argument against where they live rather than a
feature. Roughly 130 more assertions could reach Node with a one-line import change each.

Two should **not** move. `VimPathExpansionTest` tests `VimPathExpansionImpl`, which is host code.
`JdkKeyStrokeParityTest` compares against AWT on purpose, so it is JVM-only by design.

## Which tests belong on both targets, and which do not

Six more test files moved to `commonTest`, taking `jsNodeTest` from 286 to **383**: command-line
parsing, completion, comment-leader parsing, search without PSI, and the string helpers. None of
them had a reason to be JVM-bound beyond the JUnit import.

Five did **not** move, and the reason corrects the framing in the section above. `CharacterHelperTest`,
`CodePointsTest`, `RightToLeftTest`, `JdkCollectionShimsTest` and `DigraphUnicodeBlockTest` look like
tests of multiplatform helpers, and they are - but their bodies call `Character.isWhitespace`,
`Character.getDirectionality` and `java.util.StringTokenizer`. They are **differential tests against
the JDK**: the question they ask is "does the common implementation match Java", which only has an
answer on the JVM. Moving them does not broaden coverage, it deletes the comparison.

Same category as `JdkKeyStrokeParityTest` and `NumbersDifferentialTest`, both of which were always
JVM-only on purpose. The rule: **a test that names a JDK API is measuring against it, and belongs
where that API exists.**

This leaves a real gap worth naming. `RightToLeft` and the codepoint helpers are common code whose
only tests run on the JVM. The same source through the JS backend could still differ - surrogate
arithmetic is the obvious candidate. `PlatformContractsTest` covers part of that on both targets;
it does not cover all of it.

---

# Phase 2 begins: a headless injector, and the defect it found immediately

`VimInjector` is the one interface the engine reaches the outside world through, so a headless host
is exactly the subset of its 69 services that a given behaviour touches - and nobody knows that
subset from reading the code. `HeadlessInjectorBase` implements all 69 as `TODO`s that name
themselves, so running a command reports which service it wants next and the host grows to fit real
demand rather than a guess about it.

`HeadlessInjector` supplies two so far. `VimStringParserBase` leaves nothing abstract - turning
`"<C-A>"` into keystrokes is string work with no editor in it - and the logger is silent. That is
enough to build the engine's entire command list: 375 commands with their keys parsed and their
modes resolved, on both targets. The JS side moves from "these handler classes construct" to "the
command table builds".

## Two commands share one action id

The first real test found it. `getActionId` derives ids from the **simple** class name, and there
are two `InsertRegisterAction` classes - `action.change.insert` for insert-mode `<C-R>` and
`action.ex` for the command line. Both become `VimInsertRegisterAction`.
`RegisterActions.findAction` returns the first match, so **the command-line handler cannot be
reached by id at all**; the insert-mode one shadows it. That lookup is how `<Action>` mappings
resolve handlers.

It reproduces identically on both targets, so it is not a port artefact - it is pre-existing
IdeaVim behaviour that nothing had ever asserted. Not fixed here: action ids appear in users'
`.ideavimrc` `<Action>` mappings, so renaming one is a user-visible change that should be
deliberate. Pinned as current behaviour, like the two message-bundle bugs, so that fixing it fails
this test rather than passing silently. **Worth a YouTrack ticket.**

## A smaller thing the JVM half exposed

`kotlinx.serialization` is `compileOnly` in the engine - the IDE supplies it at runtime - so the
JSON providers could not read their own resources in this module's tests. Added as `runtimeOnly` for
`jvmTest` only; JS needs nothing, because it reads the generated registry rather than JSON.

## What this is and is not

It is the first behaviour the engine performs against a host that is not IntelliJ, on both targets.
It is also two services out of 69. The value of the `TODO`-per-service shape is that the next
increment is discovered rather than designed: run something, read which service it names, implement
that one.

## Vimscript parses into executable objects on both targets

Two more services, both nearly free: `VimscriptParserBase` leaves nothing abstract, and
`VimApplication` has eleven members that all have honest single-threaded answers - `invokeLater`
runs immediately, `runWriteAction` just runs the action, `isMainThread` is true because there is one
thread. A headless host is not faking those; it genuinely has nothing to marshal between.

That is enough to check the layer *above* the corpus differential. The corpus compares parse trees;
this checks the five visitors and the `Command` objects they build - `set number` becoming a
`SetCommand`, `let x = 1 + 2` keeping its `BinExpression`, `if`/`else` keeping both branches, `for`
keeping its body, and the ex-command tree resolving `s`, `d` and `g` to their full names. That is
exactly where W1's nullable-visitor decision lands, and trees agreeing was never the same as
commands agreeing.

### Host services must be lazy, and that is a requirement

Every test failed first with `lateinit property injector has not been initialized`. Engine services
read the global `injector` **in their constructors** - `VimscriptParserBase` builds a logger in its
initialiser - so a host that constructs its services eagerly touches `injector` before the
assignment installing that host has finished. Every service in `HeadlessInjector` is `by lazy` for
that reason, not for style. IntelliJ never meets this because the platform creates services on first
use.

### A shape to watch

`HeadlessInjector` lives in `commonTest`, which is right while it exists to run tests. A real VS Code
host needs the same 69 services in `jsMain`. Most of what this accumulates - parser, application,
functions, variables, registers - is genuinely host-neutral, and only the editor-touching services
should differ between a test harness and an extension. Worth resolving before there are two
implementations of the same thing rather than after.
