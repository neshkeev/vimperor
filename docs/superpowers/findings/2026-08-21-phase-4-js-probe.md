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
