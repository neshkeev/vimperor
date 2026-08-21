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

## Re-measurement, 2026-08-21 (30 of 84 core seeds retired)

The document asked for this at 20 seeds. It is now due, and the projection has moved **in our
favour** - the first correction in this port that did.

| | at re-plan | now | change |
|---|---:|---:|---:|
| direct seeds, whole engine | 241 | 126 | -115 |
| seeds in the SCC dependency closure | 84 | 54 | -30 |
| projected `commonMain` once the closure is clear | 612 (74 %) | **712 (86 %)** | +100 |
| `commonMain` today | 68 | 76 | +8 |

The projection rose because it is not a constant. Retiring a seed anywhere unblocks every file
whose *only* blocker it was, so each closure seed fixed makes the remaining ones worth more. The
612 figure was not too optimistic; it was measured against a tree with 241 seeds still in it.

**The composition changed more than the count.** Of the 54 seeds left in the closure:

| workstream | seeds in closure | seeds in leaf work | total left |
|---|---:|---:|---:|
| W2 (neutral key type) | 40 | 17 | 57 |
| W4 (JDK substitutions) | 14 | 22 | 36 |
| W6 (`com.intellij.vim.api`) | 1 | 26 | 27 |
| W1 (ANTLR) | 0 | 14 | 14 |
| W3 (reflection) | 2 | 3 | 5 |

W4 began stage 3a as the largest share of the closure (41 of 84) and is now 14 of 54. **W2 is the
critical path** and has been since roughly the halfway point of W4. The `key/` package cannot be
finished without it, and the re-plan's existing warning that W2 and W4 must be co-designed has
gone from a risk to a blocker: the W4 seeds that remain in the closure are mostly *inside* `key/`,
sitting behind the key-type decision.

**Consequence for sequencing.** Continuing to spend W4 effort outside `key/` now buys leaf files,
not core unlock. Stage 3a's remaining value is concentrated in W2. That makes the phase 2 headless
host - which this re-plan already put ahead of phase 3 - cheaper to do now than later, because it
is independent of the key type and the alternative is starting W2 without it.

## Re-measurement, 2026-08-21 (W2 complete)

W2 is done: the engine no longer knows what `javax.swing.KeyStroke` is. The section above named
W2 the critical path; that is now spent, and the picture inverted again.

| | at re-plan | after W4 collections | **now** |
|---|---:|---:|---:|
| direct seeds | 241 | 126 | **78** |
| seeds in the SCC dependency closure | 84 | 54 | **19** |
| projected `commonMain` once the closure is clear | 612 (74 %) | 712 (86 %) | **726 (87 %)** |
| `commonMain` today | 68 | 76 | **81** |

**19 files now stand between the engine and 87 % of it compiling as common code.** That is the
whole of stage 3a's remaining cost. By workstream: W4 14, W2 2, W3 2, W6 1 - and one of the 14 is
a false positive (`annotations/JetBrainsAnnotations.jvm.kt` is a JVM `actual` and belongs in
`jvmMain` permanently), so the real figure is 18.

### The 19, and what each is waiting on

| blocker | files | shape of the work |
|---|---|---|
| `ConcurrentLinkedDeque` | `KeyHandler`, `VimListenersNotifier` | concurrency - the spec warns that replacing it could mask a real JVM race; needs single-threaded access proven, not assumed |
| `java.text.DecimalFormat` | `VimFloat` | number formatting, output is user-visible |
| `ResourceBundle` + `MessageFormat` | `EngineMessageHelper` | i18n; needs a host-provided message source |
| `Transferable` | `VimClipboardManager` | clipboard, genuinely per-host |
| `Timer` + `ActionListener` | `MappingState` | timers, genuinely per-host |
| `java.nio.file.Path` | `VimscriptExecutor` | filesystem, genuinely per-host |
| `System.getenv` | `Options` | environment access, genuinely per-host |
| `Pattern` | `KeywordOptionHelper` | `kotlin.text.Regex`, but the semantics differ |
| `CharBuffer` | `EngineEditorHelper` | mechanical |
| `Character.UnicodeBlock` | `CharacterHelper` | needs a Unicode-block table |
| `MethodHandles` / `KClass` / `javaClass` | `LazyInstance`, `LazyExCommandInstance`, `VimLogger`, `EditorActionHandlerBase`, `LazyVimCommand` | W3, the de-reflection workstream |
| `com.intellij.vim.api` | `VimHighlightingService` | W6 |
| `@Throws(java.lang.Exception::class)` | `VimProcessGroup` | trivial |

The shape of the remaining work has changed character. Up to here stage 3a was bulk substitution;
what is left is mostly **host services** - clipboard, timers, filesystem, environment, messages -
which want a host interface, not a shim. That is the same interface phase 2's headless host needs,
which is a second reason to stop deferring it.

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
