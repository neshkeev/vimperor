# Phases 2–4, re-planned against phase 1's measurements

**Supersedes** §7 (phases 2–4) of `specs/2026-08-16-vim-engine-multiplatform-design.md`.
**Basis:** `findings/2026-08-21-phase-1-result.md`.
**Written because** the spec's sequencing rests on "~95 % of the engine moves to `commonMain`",
which phase 1 measured at **7.8 %**.

---

## What survives unchanged

**Phase 2 (headless host + `VimTestCase` re-point) stands exactly as specified.** Its gate —
the 598-file suite green on *both* hosts, both on the JVM — is runtime-independent and does not
depend on how much code is in `commonMain`. The reasoning behind it is untouched: prove the
headless host equivalent while the runtime is still constant, so a phase-4 failure is
unambiguously a JS divergence. **Do phase 2 next, as written.**

Also unchanged: the non-goals, the narrow-waist test strategy, phase 5, and the deferred
question of when to drop the IntelliJ plugin.

## What changes

### 1. The workstream list was wrong

| Spec | Reality |
|---|---|
| W5 (`kotlinx.serialization` / `coroutines`) | **Delete it.** Both are fully multiplatform; the `-jvm` artifacts are a build-file concern. Gates nothing. |
| — | **Add W6:** `:api` has no common variant. 27 files. Cheapest workstream, worth 0 files alone. |
| W4 = "JDK substitutions" | Also covers **`org.jetbrains.annotations`** (43 files), which the spec missed. |
| W1 deferred out of phase 1 | Still deferred, still real: 14 files. Belongs in phase 3. |

Phase 3 is therefore **W1, W2, W3, W4, W6** — not "W2–W5".

### 2. Phase 3 must be sequenced by dependency depth, not by workstream

This is the substantive change.

The spec says "Each step moves code from `jvmMain` into `commonMain`", implying steady
progress. **It does not work that way.** Organised by workstream, nothing moves until the last
one lands:

| Resolved | `commonMain` |
|---|---:|
| nothing | 7.8 % |
| any one workstream | 9.1 – 9.8 % |
| three of four | 11.3 % |
| all | 100 % |

The cause is a **163-file strongly-connected component** around `VimEditor`, `VimCaret`,
`Command`, `CommandBuilder`, `EditorActionHandlerBase`. It moves as a unit, and 728 files sit
downstream of it — 421 of which have **no blocker of their own** and are waiting purely on it.

But the SCC is not unlocked by fixing the SCC. Fixing all 76 seeds *inside* it changes
`commonMain` by **zero**, because it also depends on seeds outside itself. What unlocks it is
its **dependency closure**: 223 files, containing **84 seeds**.

> **Fix those 84 files and `commonMain` goes from 68 to 612 — 8 % to 74 %.**
> Touch 84, unlock 544.

The 84 are listed in `2026-08-21-phase-3-core-unlock-seeds.tsv`. Composition:

| | files |
|---|---:|
| blocked only by W4 (`java.util` etc.) | 41 |
| blocked only by W2 (key types) | 29 |
| blocked by both | 11 |
| W3 / W6 | 3 / 1 |

By package: 24 `api`, 20 `key` + `key/consumers`, 5 `helper`, 5 `command`, 4 `common`, rest
scattered.

After that, **157 seeds remain as leaf work** — W4 112, W6 26, W2 17, W1 14, W3 5 — mutually
independent, parallelisable, and each one now yields visible incremental progress.

### 3. Phase 3's shape

**Stage 3a — unlock the core (the 84).** One mixed-workstream batch, not four sequential
workstreams. It needs W2's neutral key type and W4's JDK substitutions designed *together*,
because 11 files need both and the `key/` package is where they meet.
*Gate: suite green on both hosts; `commonMain` ≥ 600 files.* This is the phase's real
milestone and its main risk.

**Stage 3b — leaf mop-up (the 157).** Independent, parallelisable, individually revertible.
*Gate: both hosts green after every step; `commonMain` count strictly increasing.*

**Stage 3c — W1 (ANTLR → antlr-kotlin).** Deferred since phase 0, unchanged in scope: 14 files,
35 porting sites, the caveats in `findings/2026-08-16-antlr-kotlin-gate.md` still apply. It is
independent of 3a/3b and can run concurrently by a different pair of hands.

### 4. Progress metric

**Do not track phase 3 by `commonMain` file count during stage 3a.** It stays flat at ~8 % for
the entire stage and then jumps to 74 % on the last file. Track **seeds retired out of 84**,
which moves every day. The file count becomes a valid metric only in stage 3b.

A plan that reports "files moved to commonMain" through 3a will look stalled for its whole
duration and then complete all at once. This has already caused one wrong estimate; it should
not cause a second.

### 5. Phase 4's entry condition tightens

The spec's phase 4 is "`commonMain` compiles for JS". There is no partial version of this: a JS
target added before stage 3a completes can compile 8 % of the engine and cannot run the suite,
so it produces no signal. **Phase 4 starts after stage 3a, not before.** Stage 3b can overlap
with it — 74 % is enough to exercise the JS toolchain meaningfully.

## Estimate honesty

Phase 1's spec estimate was wrong by 12×. The numbers here are measured from the current tree,
not estimated — but they share one method: a static dependency graph that **over-approximates
edges** and whose symbol table already proved **optimistic by 9 files** in phase 1. Treat
"84 files unlocks 612" as accurate in shape and approximate in magnitude. Stage 3a should
re-measure after the first 20 seeds and correct this document rather than defend it.

## Risks added since the spec

| Risk | Stage | Mitigation |
|---|---|---|
| Stage 3a is one big-bang batch with no partial credit — if it stalls, phase 3 shows nothing for its whole duration | 3a | Track seeds retired, not files moved; re-measure at 20 seeds |
| The neutral key type (W2) and JDK substitutions (W4) must be co-designed; the spec treats them as separate workstreams | 3a | 11 files need both; design them together before touching `key/` |
| The 74 % figure rests on a heuristic that was optimistic once already | 3a | Re-measure and correct after the first 20 seeds |
| Phase 4 started early yields a JS target that compiles 8 % and proves nothing | 4 | Explicit entry condition: stage 3a complete |
