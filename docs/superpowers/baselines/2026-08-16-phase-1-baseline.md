# Phase 1 baseline — pre-Multiplatform

Recorded before any `vim-engine` source-set restructuring. Phase 1's gate is "no behavior
change", which is only checkable against a measured starting point. This is that point.

**Commit:** `6c300b697` (master, immediately after phase 0 cleanup)
**Date:** 2026-08-16

---

## Build environment

**JDK 21 is mandatory and PATH `java` is not it.** The root build hard-fails at
`build.gradle.kts:188` with "Incorrect java version used for building" — in 4 seconds, before
compiling anything. PATH `java` on this machine is Corretto 17.0.16.

```
export JAVA_HOME=/Users/neshkeev/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home
```

This is the same class of problem Ruling 1 solved for the phase 0 spike (which used the foojay
resolver instead). Worth knowing before diagnosing a phase 1 failure that isn't one.

## Suite result

```
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain
```

| | |
|---|---|
| Tests | **12,645** |
| Failures | **0** |
| Errors | **0** |
| Skipped | 99 |
| Wall clock | 17m 32s |
| Result | `BUILD SUCCESSFUL` |

Property tests and long-running tests are excluded per `CLAUDE.md`. They are not part of this
gate; if phase 1 is suspected of a behavior change they are the next thing to run, not the first.

**Phase 1 gate restated concretely:** IntelliJ plugin builds, and this suite reports 12,645
tests / 0 failures / 0 errors. A change in the *test count* is as much a gate failure as a
failure count — it means tests stopped being discovered, which source-set moves can cause
silently.

## Baseline measurements re-verified

The spec (§3) says to re-measure if picked up later. Every figure was re-checked against the
tree at `6c300b697` and **all of them hold**:

| Property | Spec §3 | Re-measured | |
|---|---|---|---|
| `vim-engine` size | 840 files, ~79k lines | 840 files, 79,094 lines | ✓ |
| IntelliJ Platform coupling | 4 stray references | 4 | ✓ — but see below |
| `javax.swing.KeyStroke` | 55 files | 55 | ✓ |
| `java.awt.event.KeyEvent` / `InputEvent` | 26 files | 21 + 5 = 26 | ✓ (sum; union is 22, so 4 files use both) |
| `kotlin.reflect.full.*` call sites | 6 | 6 | ✓ |
| `kotlinx.serialization` / `kotlinx.coroutines` | 4 / 6 files | 4 / 6 | ✓ |
| ANTLR grammar lines | 1,829 | 1,829 | ✓ |

Two notes on scope, since both tripped a first measurement pass:

- The `840 files / ~79k lines` figure is **module-wide `.kt`, including tests**. `src/main`
  alone is 823 files / 73,173 lines.
- The `6 reflection call sites` figure counts **call sites, not imports**. There are 3
  `kotlin.reflect.full.*` imports and 6 uses (`createType()` ×5, `primaryConstructor` ×1),
  in `CommandVisitor.kt` and `VimVariableServiceBase.kt`.

### The IntelliJ coupling is documentation, not code

All four references are in **KDoc comments**:

| Site | Reference |
|---|---|
| `handler/EditorActionHandlerBase.kt:63` | `com.intellij.openapi.command.CommandProcessor` (prose) |
| `extension/ExtensionHandler.kt:23` | `com.intellij.openapi.command.CommandProcessor` (prose) |
| `extension/ExtensionHandler.kt:25` | `com.intellij.openapi.application.Application` (prose) |
| `helper/CommenterToComments.kt:11` | `com.intellij.lang.Commenter` (prose) |

There is **no compiled dependency on the IntelliJ Platform anywhere in `vim-engine/src/main`**.
Every other `com.intellij.*` reference in the module is IdeaVim's own `com.intellij.vim.*`
packages. The spec budgeted these four as work; they are not. Moving the engine to `commonMain`
does not touch the platform at all.

This is better than the spec assumed and slightly de-risks phase 1.

## Test-count reference for later phases

Phase 2's double-green gate and the "quarantined test count must not grow" risk control both
need a denominator. It is **12,645 tests / 99 skipped**, not the 653 *files* the spec cites —
files and tests differ by ~19×, and the risk table's "track quarantined test count as an
explicit reported metric" is only meaningful in tests.
