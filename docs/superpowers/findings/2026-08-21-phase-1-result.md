# Phase 1 result — Multiplatform scaffolding, jvm target only

**Branch:** `spike/phase1-antlr-kmp-gate` (unmerged)
**Base:** `9495fcf1c` · **Head:** `34a02233b` + task 7
**Plan:** `docs/superpowers/plans/2026-08-16-phase-1-multiplatform-scaffolding.md`

**Phase 1 is done and green.** `vim-engine` and `api` are Kotlin Multiplatform modules with a
`jvm` target, 64 engine files compile in `commonMain`, and the IntelliJ plugin builds, passes
its full suite, and runs.

---

## 1. Gate

| Check | Result |
|---|---|
| `buildPlugin` | **SUCCESSFUL**, 7.88 MB ZIP |
| Standard suite | **12,645 / 0 failures / 0 errors / 99 skipped** — exact baseline match |
| Clean, cache disabled | yes — `clean` + `--no-build-cache` |
| All 8 consumer modules | compile and jar |
| 4 KSP JSONs in the jar | present, **byte-identical to git** |
| `runIde` manual smoke | **passed** — motion, `:` ex commands, search, `.ideavimrc` mappings |

Baseline is `docs/superpowers/baselines/2026-08-16-phase-1-baseline.md` (12,645 at `6c300b697`).

The smoke test included the user's own `~/.ideavimrc`, which `source`s a `~/.vimrc` containing
a vimscript function with a dictionary literal, `items()`, a destructuring `for [k, v] in`, and
`execute` with string concatenation. That exercises far more of the ANTLR vimscript grammar
than any `:set` does, and it works — the strongest available evidence that the hand-rolled
`JavaExec` ANTLR invocation replacing the `antlr` Gradle plugin is correct on real input.

## 2. Final layout

| | files |
|---|---:|
| `vim-engine/src/commonMain/kotlin` | **64** |
| `vim-engine/src/jvmMain/kotlin` | 759 |
| `vim-engine/src/jvmTest/kotlin` | 17 |
| `vim-engine/antlr/*.g4` | 3 (target-neutral) |

Phase 1 changed **zero files under `src/`** (the IntelliJ plugin side). Total diff: 941
`vim-engine` paths, 1 `api` path, 5 docs. Every engine source change is a pure rename except
one line in `ForbiddenApiTest.kt` (`src/main` → `src/jvmMain`).

## 3. The spec's "~95 %" was wrong. The real figure is 7.8 %.

64 of 823. Not a shortfall in execution — a wrong estimate. The spec counted files that
*directly* reference JVM APIs (~190, roughly right) and assumed the rest were portable. They
are not: **516 of the 759 jvm-bound files have no JVM reference of their own** and are blocked
purely by what they depend on.

The obstacle is an **88-file strongly-connected component** — `VimEditor`, `VimCaret`,
`Command`, `CommandBuilder`, `EditorActionHandlerBase`, `VimMarkServiceBase`, `Options`,
`EngineEditorHelper` and friends form a dependency cycle, so they move as a unit or not at all.
It contains 24 direct seeds spanning **every** workstream, and **723 of 823 files depend on
it**. Full derivation in `plans/2026-08-16-phase-1-task-4-classification.md`.

## 4. Phase 3's work-list

759 files in `jvmMain`. Direct seeds by workstream (a file may carry several):

| WS | What | Direct seeds | Transitive, nearest-seed attribution |
|---|---|---:|---:|
| W4 | `java.util`, `java.io/nio`, `java.text/time`, `Locale`, `java.lang`, `org.jetbrains.annotations` | 167 | 501 |
| W2 | `javax.swing.KeyStroke`, `java.awt.*` | 57 | 35 |
| W6 | `com.intellij.vim.api.*` — `:api` has no common variant | 27 | 0 |
| W1 | ANTLR / `org.antlr.*` | 14 | 0 |
| W3 | `kotlin.reflect.full`, `java.lang.reflect`, `::class.java` | 8 | 5 |

Per-file detail: `plans/2026-08-16-phase-1-task-4-move-list.tsv` (823 rows).

### The scheduling consequence

**The workstreams have no useful partial ordering.** Counterfactual closure:

| Resolved | `commonMain` |
|---|---:|
| nothing (today) | 7.8 % |
| any one workstream | 9.1 – 9.8 % |
| three of four | 11.3 % |
| **all** | **100 %** |

Finishing three of four buys ~20 files; the fourth buys ~730. No single file is a useful choke
point either — removing any one seed frees at most 4 others.

**Track phase 3 by blockers retired, never by `commonMain` file count.** The file count stays
near 9 % for almost the entire effort and then jumps. A plan that reports progress by files
moved will look stalled for months and then finish all at once.

## 5. What the spec got wrong, beyond the 95 %

1. **`~95 %` → 7.8 %**, for the reason above.
2. **W6 did not exist.** `:api` has no common variant, blocking 27 engine files. Scoped in
   `plans/2026-08-16-phase-1-w6-api-module-scoping.md`: it is the *cheapest* workstream (33
   files, 4 blockers, all `org.jetbrains.annotations`) and worth **zero files** on its own,
   because 24 of the 27 are `thinapi/` bridge code that also reaches the core SCC. Sequence it
   with W1–W4, not before.
3. **`org.jetbrains.annotations` was missing from the blocker table** — 43 files.
4. **W5 (`kotlinx.serialization` / `kotlinx.coroutines`) is not a source blocker.** Both are
   fully multiplatform; the `-jvm` artifacts are a build-file concern. It gates nothing.
5. **The `antlr` Gradle plugin cannot survive KMP at all** — it applies `java-library`, which
   KGP hard-errors on. Neither option the plan enumerated was possible. Resolved by dropping
   the plugin and keeping the tool (`plans/2026-08-16-phase-1-task-1-antlr-kmp-gate.md`).

## 6. The methodological lesson: green builds that were wrong

Four times in this phase a build passed while being wrong. None would have been caught by
"did it pass?":

1. **Stale Gradle build cache** after moving 823 files — `BUILD SUCCESSFUL` with 184 classes
   instead of 1,689. Fixed by `--no-build-cache`.
2. **`sourcesJarArtifacts` deleted** with the `java` plugin — `test` green, `buildPlugin`
   broken. The suite alone is not a sufficient gate.
3. **KMP renamed `test` → `jvmTest`**, and `./gradlew test` matches by task *name*, so 530
   engine tests silently stopped running. 0 failures, green build — and **one of those 530 was
   genuinely failing**. The silent skip was hiding a real failure, not just absent coverage.
4. **`:test UP-TO-DATE`** — a gate run that returned `BUILD SUCCESSFUL in 32s` without
   executing a single test.

Every one was caught by checking the **test count** and `buildPlugin`, never by the colour of
the build. Both belong in every future gate, and gate runs must be `clean` +
`--no-build-cache`.

A fifth, in the analysis rather than the build: task 4's file classification was **optimistic
by 9 files** because its symbol-extraction regex missed `lateinit var` and mis-parsed extension
functions. The compiler found it, as the plan required. Heuristics size the work; only
compilation decides it.

## 7. Not done / carried forward

- **The branch is unmerged to `master`.**
- **Publishing is rewritten but unverified.** KMP appends `-jvm` to the platform artifact and
  adds a root `*-kotlin-metadata` module, so **published coordinates change**. Nothing was
  published or resolved. `engineVersion` and `uploadUrl` are both empty in
  `vim-engine/gradle.properties`, so this appears vestigial — worth confirming before phase 2.
- **Bundled sources-jar layout gained a `jvmMain/` prefix.** Cosmetic; uninvestigated.
- **`withJavadocJar()` is gone** and was not replaced.
- **Two files returned to `jvmMain`** by task 5: `history/HistoryBlock.kt`,
  `options/helpers/ClipboardOptionHelper.kt`, both on `injector` / `globalOptions`.
- **Unrelated pre-existing bug observed during the smoke test:** a Disposer leak at
  `ui/ex/GlassPaneManager.kt:56` (ex-entry panel registers a mouse-motion preprocessor and
  never disposes it), reported as SEVERE with "Plugin to blame: IdeaVim". Introduced by
  `cf2ea108c` (Matt Ellis, 2026-05-29), which is an ancestor of `master`. **Not phase 1** —
  phase 1 changed nothing under `src/`. Worth a YouTrack ticket.

## 8. Baseline for phase 2

**12,645 tests / 0 failures / 0 errors / 99 skipped**, unchanged from phase 1's entry baseline.
Gate command:

```
export JAVA_HOME=<jdk-21>
./gradlew clean
./gradlew buildPlugin test -x :tests:property-tests:test -x :tests:long-running-tests:test \
  --console=plain --no-build-cache
```

Then assert the count, not the colour.
