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
