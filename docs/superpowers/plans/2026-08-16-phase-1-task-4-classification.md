# Phase 1 Task 4 — commonMain/jvmMain classification: result

**Plan:** `docs/superpowers/plans/2026-08-16-phase-1-multiplatform-scaffolding.md` task 4
**Branch:** `spike/phase1-antlr-kmp-gate`
**Move-list:** `2026-08-16-phase-1-task-4-move-list.tsv` (823 rows, one per source file)
**Analysis only. Nothing was moved.**

---

## Headline

| | files | share |
|---|---:|---:|
| `commonMain` | **73** | **8.9 %** |
| `jvmMain` | 750 | 91.1 % |
| — of which *directly* blocked | 195 | 23.7 % |
| — of which blocked only *transitively* | 555 | 67.4 % |

**The spec's "~95 % of today's engine moves to `commonMain`" is wrong by an order of
magnitude.** The achievable figure in phase 1 is ~9 %. Per the plan's own instruction, this is
reported as a finding about the spec, not as a failure of the task.

The spec's error is not in its blocker inventory — that was close to right (see §4). It is that
the spec counted **direct** references only. Two thirds of the module is jvm-bound purely by
transitivity, and transitivity is what the plan explicitly warned would matter.

## 1. Direct blockers (195 files)

| Workstream | Blocker | Files |
|---|---|---:|
| W4 | `java.util.*` | 111 |
| W2 | `javax.swing.KeyStroke` | 55 |
| W2 | `java.awt.*` | 23 |
| W4 | `java.lang.*`, `isJavaIdentifierPart`, `Thread`, `System.getProperty` | 17 |
| W1 | ANTLR (`parser.generated`, `org.antlr.*`) | 14 |
| W4 | `java.io.*` / `java.nio.*` | 9 |
| W4 | `java.text.*`, `java.time.*`, `Locale` | 9 |
| W3 | `kotlin.reflect.full`, `java.lang.reflect`, `::class.java` | 8 |

Distinct files per workstream (a file may carry several): **W4 134, W2 57, W1 14, W3 8**.

Two blockers the plan listed are **not** source blockers and were dropped after checking:

- **W5 — `kotlinx.serialization` / `kotlinx.coroutines`.** Both are fully multiplatform. The
  plan's concern was the `-jvm` suffixed artifacts, which is a `build.gradle.kts` dependency
  declaration, not anything in the 10 affected source files. W5 does not gate any file.
- **`@JvmStatic` / `@JvmField` / `@JvmOverloads` / `@JvmName` / `@Throws`** (19 files). These are
  declared in the common stdlib as optional expectations and compile in `commonMain` unchanged.

## 2. The real obstacle: a 88-file mutually-recursive core

The module has one large strongly-connected component — **88 files** that form a dependency
cycle and therefore **can only move as a unit**. It is centred on the engine's primary
abstractions: `VimEditor`, `VimCaret`, `Command`, `CommandBuilder`, `EditorActionHandlerBase`,
`VimMarkServiceBase`, `Options`, `EngineEditorHelper`.

A confirmed cycle, every edge checked by hand:

```
api/VimEditor.kt          --  fun carets(): List<VimCaret>            (same package)
  -> api/VimCaret.kt      --  val markStorage: LocalMarkStorage       (declared at
                                 api/VimMarkServiceBase.kt:730)
  -> api/VimMarkServiceBase.kt  --  import ...command.Command  (:26)
                                --  import java.util.*         (:36)   <- W4 seed
  -> command/Command.kt   --  import ...handler.EditorActionHandlerBase (:11)
                            --  import java.util.*                     (:12)  <- W4 seed
  -> handler/EditorActionHandlerBase.kt -- import ...api.VimEditor     (:13)
  -> back to VimEditor.kt
```

Two facts make this the whole story:

1. **24 of the 88 files are direct seeds, and they span all four workstreams** (W1, W2, W3, W4).
   The SCC is jvm-bound until *every* one of those 24 is freed.
2. **723 of 823 files transitively depend on this SCC.** So nothing downstream of it can move
   either.

## 3. Consequence: the workstreams cannot be sequenced for incremental value

Counterfactual — recompute the closure with each workstream's seeds deleted:

| Resolved | `commonMain` | share |
|---|---:|---:|
| *(nothing — today)* | 73 | 8.9 % |
| W3 alone | 75 | 9.1 % |
| W2 alone | 78 | 9.5 % |
| W1 alone | 79 | 9.6 % |
| W4 alone | 81 | 9.8 % |
| W4 + W1 | 88 | 10.7 % |
| W4 + W1 + W2 | 93 | 11.3 % |
| **all four** | **823** | **100 %** |

Finishing three of the four workstreams buys **20 files**. Finishing the fourth buys **730**.

This is the single most important planning consequence of task 4: **phase 3 has no useful
partial-credit ordering.** Any plan that schedules W1→W2→W3→W4 expecting `commonMain` to grow
steadily will show essentially flat progress until the final workstream lands. Progress in
phase 3 must be tracked by *blocker count retired*, never by `commonMain` file count — the
latter stays near 9 % for almost the entire effort and then jumps to 100 %.

No individual file is a useful choke point either: removing any single seed frees at most **4**
other files. There is no high-leverage first target.

## 4. Where the spec was right and wrong

Right: the blocker inventory. Measured vs the spec's counts — ANTLR 14 vs "10", `KeyStroke` 55
vs "55" (exact), `java.awt` 23 vs "22", reflection 8 vs "2 files / 6 call sites".

Wrong: the "~95 %" estimate, and the omission of any cycle analysis. The spec appears to have
reasoned "823 files, ~190 touch JVM APIs, so ~95 % is clean." That is true of *direct*
references and irrelevant to what can actually compile in `commonMain`.

## 5. What this means for task 5

Task 5 moves 73 files, not ~780. It is a much smaller job than planned, and its per-batch gate
(12,645 / 0 plus `buildPlugin`) is cheap at that size — the 73 files fit in two batches.

The 73 are leaf-like by nature: 22 in `api`, 15 in `common`, then singles and pairs across 22
further packages. Full per-file detail is in the TSV.

## 6. Method and its limits

Blockers detected by regex over comment- and string-stripped source. Dependency edges from
explicit `import com.maddyhome.*` statements, star imports, and same-package symbol references
(Kotlin requires no import within a package). Top-level declarations only. Closure computed by
propagating jvm-boundness backwards along those edges to a fixed point.

The edge rule **over-approximates**: a same-package token match counts as a dependency even
where the reference is not a type reference. So the true `commonMain` count is **73 or slightly
higher**, never lower. Graph shape was checked for the artefacts this could cause — mean
out-degree 6.9, median 7 — which is ordinary, not the near-complete graph that would make the
all-or-nothing result an artefact. The core cycle was then confirmed edge-by-edge by hand
(§2), so the finding does not rest on the heuristic.

Not verified by compilation. Task 5 is what proves each of the 73 actually compiles in
`commonMain`; the plan already requires that any file which fails goes back to `jvmMain` with
its blocker recorded.
