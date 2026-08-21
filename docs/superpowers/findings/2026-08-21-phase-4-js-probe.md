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
