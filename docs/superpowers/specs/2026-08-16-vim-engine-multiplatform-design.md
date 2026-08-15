# vim-engine → Kotlin Multiplatform

**Date:** 2026-08-16
**Status:** Approved design, ready for implementation planning
**Scope:** Sub-project 1 of the IdeaVim → VS Code port

---

## 1. Program context

This spec covers the first sub-project of a larger effort: porting IdeaVim to a VS Code
extension. The program-level decisions below were settled during design and constrain
everything in this document.

| Decision | Choice |
|---|---|
| Intent | Serious long-term port, intended to ship |
| Upstream relationship | Hard fork — `vim-engine` may be changed freely, no upstreaming obligation |
| Parity target | Full IdeaVim parity **plus** first-class VS Code integration (commands, LSP actions, peek, terminal, multi-cursor) |
| Engine hosting | **Kotlin Multiplatform compiled to JS, running in-process in the extension host** |

### Why Multiplatform-to-JS rather than a JVM sidecar

The rejected alternative was a bundled JVM running `vim-engine`, driven over JSON-RPC from a
TypeScript extension (the `vscode-neovim` model). That approach reuses the engine almost
untouched and reaches a working product sooner.

It was rejected because the largest deliverable in the whole program is the VS Code host
adapter, and under the sidecar model that adapter gets written against a process boundary the
project would eventually want to delete. Building roughly 100k lines of adapter on top of an
architecture intended for replacement is the expensive mistake available here. The sidecar
also forces a bundled JRE (~50MB, platform-specific VSIXs), rules out web VS Code, and leaves
two runtimes to debug permanently.

A Kotlin/Native sidecar was considered and dismissed as strictly dominated: it pays the full
Multiplatform refactor cost *and* keeps the process boundary.

A TypeScript rewrite was dismissed as multi-year work that converges on reproducing
VSCodeVim, forfeiting the differentiator.

### Program decomposition

The port is too large for one spec. It decomposes into eight sub-projects, each with its own
design → plan → implementation cycle. This spec is **sub-project 1**.

| # | Sub-project | Done when |
|---|---|---|
| **1** | **vim-engine → Multiplatform** | **This spec** |
| 2 | Vertical slice host | `hjkl`, `i`/`Esc`, `x`, `dw` work in a real VS Code editor; mode in status bar |
| 3 | Editor & document adapter | Engine behavioral tests pass against the VS Code host |
| 4 | Vim UI surfaces (`:`, `/`, output panel, modal input) | Ex commands and search usable with history and completion |
| 5 | Config & Vimscript runtime | A real `.ideavimrc` loads and applies |
| 6 | VS Code natives integration | Vim keys can drive arbitrary VS Code commands |
| 7 | Extensions (surround, easymotion, …) | Ports run against the ported extension API |
| 8 | Packaging & release | Installable from the marketplace |

Order: 1 → 2 → 3 → (4 ∥ 5) → 6 → 7 → 8. Sub-project 3 is expected to need further splitting
once designed.

Two known hazards live in sub-project 3, recorded here so they are not rediscovered as
surprises:

- **Sync/async mismatch.** `VimEditor`/`VimCaret` assume IntelliJ's synchronous in-process
  document model. VS Code's `TextEditor.edit()` is asynchronous, the extension host is
  out-of-process from the renderer, and edits can be rejected. Reconciling a synchronous Vim
  engine with an asynchronous document API is the central design problem of the port — larger
  than this sub-project.
- **Undo.** Vim's undo semantics and VS Code's undo stack are both authoritative and both
  stateful. IdeaVim reconciles this with IntelliJ-specific command grouping that has no VS
  Code equivalent.

---

## 2. Goal and done-condition

Convert `vim-engine` from a Kotlin/JVM library into a Kotlin Multiplatform library with `jvm`
and `js` targets, **changing no Vim behavior**.

Done when all four hold:

1. `commonMain` compiles for both `jvm` and `js`, with no `expect`/`actual` declarations
   outside a small, explicitly enumerated platform layer.
2. The IntelliJ plugin still builds, and its 653-file test suite still passes on the `jvm`
   target.
3. The ported behavioral suite (the 598 `VimTestCase` subclasses, less any explicitly
   quarantined and counted) runs against the `js` target under Node and passes.
4. No VS Code code exists. This sub-project ships a library, not an extension.

Condition 3 is the expensive one; section 5 is entirely about it.

### Explicit non-goals

- Any VS Code extension code, manifest, or packaging.
- Any Vim behavior change, bug fix, or feature addition. Behavior-preserving is the whole
  point; a green suite is only meaningful if the suite is unchanged.
- Removing the IntelliJ plugin (see section 8, open question).
- Performance optimization of the JS target beyond "not pathologically slow". Real
  performance work belongs with sub-project 2, where keystroke latency becomes observable.

---

## 3. Baseline measurements

Taken from the repository at design time. These justify the estimates below and should be
re-measured if this spec is picked up much later.

| Property | Value |
|---|---|
| `vim-engine` size | 840 files, ~79k lines |
| IntelliJ Platform coupling in `vim-engine` | 4 stray references (`com.intellij.openapi.command`, `openapi.application`, `lang`) — everything else under `com.intellij.*` is IdeaVim's own `vim.annotations` / `vim.api` packages |
| `javax.swing.KeyStroke` | 55 files |
| `java.awt.event.KeyEvent` / `InputEvent` | 26 files |
| ANTLR grammars | 1,829 lines across `Vimscript.g4`, `RegexLexer.g4`, `RegexParser.g4` |
| `kotlin.reflect.full.*` call sites | 6 |
| `java.util.*` imports | ~110, mostly wildcard; ~15 distinct real types |
| `kotlinx.serialization` / `kotlinx.coroutines` | 4 / 6 files |
| `vim-engine` own tests | 17 files |
| **Behavioral suite (`src/test`)** | **653 files, ~149k lines** |
| Of those, extending `VimTestCase` | **598** |
| `VimTestCase` | 1,242 lines, 40 IntelliJ imports, `src/testFixtures/` |

The decisive finding: `vim-engine` is portable away from IntelliJ but **not** away from the
JVM, and the behavioral suite's IntelliJ coupling is concentrated in one file rather than
spread across 653.

---

## 4. Module and source-set structure

```
vim-engine/
  src/commonMain/kotlin      ← ~95% of today's engine, logic unchanged
  src/jvmMain/kotlin         ← actuals: regex, locale, concurrency, clipboard, timer
  src/jsMain/kotlin          ← actuals: same surface, JS implementations
  src/commonTest/kotlin      ← the ported behavioral suite (section 5)
  src/jvmTest/kotlin         ← quarantined JVM-only tests
api/                          → Multiplatform, same treatment (~10 small files)
annotation-processors/        → stays JVM-only
```

`annotation-processors` needs no porting. KSP is a **compile-time** dependency that emits JSON
into resources (`engine_commands.json`, `engine_ex_commands.json`,
`engine_vimscript_functions.json`, `ideavim_extensions.json`). Those files are consumed at
runtime. The only change required is teaching the JS target to load them as embedded string
constants, since JS has no classpath.

The platform layer (`expect`/`actual`) is deliberately bounded to: regex primitives, locale
and case conversion, concurrency primitives, clipboard transfer, timers, and resource loading.
Anything else appearing in `jvmMain` at the end of phase 3 is a defect in this design and
should be raised rather than accommodated.

---

## 5. Portability workstreams

Ordered by risk. W1 gates everything; W2–W5 may run in parallel once it clears.

### W1 — ANTLR → antlr-kotlin *(highest risk)*

The engine depends on more than generated parsers. It subclasses the ANTLR **runtime**:
`BailErrorLexer` extends `Lexer`, `VimRegexParserErrorStrategy` extends `DefaultErrorStrategy`.
It also uses `CharStreams`, `CharStream`, `CommonTokenStream`, `ParserRuleContext`, `ParseTree`,
`TerminalNode`, `Token`, `Recognizer`, `BaseErrorListener`, `RecognitionException`,
`LexerNoViableAltException`.

So a real ANTLR runtime is needed on JS, not merely generated code. `antlr-kotlin` provides
one, but the error-strategy subclassing is exactly where a reimplementation is most likely to
diverge from the reference Java runtime.

**This is a go/no-go gate for the entire approach.** See phase 0.

### W2 — Neutral key type

Despite touching 81 files, the API surface actually used is four members: `.keyChar` (60
uses), `.keyCode` (48), `.modifiers` (17), and `KeyStroke.getKeyStroke(…)` (31).

Introduce a `VimKeyStroke` value type plus a `VimKeyCodes` object holding the `VK_*` integers.
**Keep AWT's numeric values verbatim** so no `.ideavimrc`, mapping, or macro semantics shift.
High-volume, mechanical, low-risk.

### W3 — De-reflection

Six call sites in the Vimscript type-conversion machinery: `createType`, `primaryConstructor`,
`KType`, `KClass`, `KProperty`, `KFunction`. `kotlin.reflect.full.*` is JVM-only. Replace with
explicit registration.

### W4 — JDK substitutions

`EnumSet` → `Set`; `Locale` → explicit case handling; `Pattern` → `kotlin.text.Regex`;
`Collectors` → stdlib; `ConcurrentHashMap` / `ConcurrentLinkedDeque` → plain collections.

The concurrency substitution rests on the engine being single-threaded per editor. **Confirm
this rather than assume it** — if any of those collections is genuinely shared across threads
on the JVM host, the substitution is a latent race on the JVM target even though JS is
single-threaded.

`javax.swing.Timer` and `java.awt.datatransfer.Transferable` are already behind injector
interfaces and move to the platform layer.

### W5 — Coroutines and serialization

Both libraries already publish JS targets. The change is dependency coordinates (`-jvm`
artifacts → multiplatform artifacts). 6 and 4 files affected respectively. Near-free.

---

## 6. Test strategy

### The problem

598 test files, ~149k lines, all funnelling through `VimTestCase`, all bound to IntelliJ
fixtures. The JS target needs behavioral coverage or the engine is being ported blind.

### The narrow waist

Across all 598 files, the `VimTestCase` surface actually consumed is 16 methods, six of which
dominate:

| Method | Call sites |
|---|---|
| `enterCommand` | 4,509 |
| `typeText` | 3,845 |
| `doTest` | 2,777 |
| `configureByText` | 2,122 |
| `assertState` | 1,744 |
| `assertPluginError` | 969 |
| `assertPosition` | 396 |
| `assertExOutput` | 289 |
| `assertOffset` | 204 |
| `configureByPages` | 190 |
| `assertMode` | 176 |
| `assertSelection` | 166 |
| `enterSearch` | 147 |
| `configureByLines` | 119 |
| `assertVisualPosition` | 34 |
| `configureByXmlText` | 3 |

~149k lines of Vim-correctness knowledge sits behind 16 methods. Re-point those and the suite
moves.

**Caveat on this count.** These figures come from a targeted grep for a hand-written list of
method names, so the list is a measured floor, not a proven-exhaustive surface. Enumerating
`VimTestCase`'s full public and protected surface — and the subset genuinely reachable from
the 598 subclasses — is a phase 0 sizing task. The design does not depend on the number being
exactly 16; it depends on the surface being small and enumerable, which the distribution above
strongly indicates.

**The other 55 files.** 653 test files exist; 598 extend `VimTestCase`. The remaining 55 are
not covered by the waist and do not automatically port. Expect most to be IntelliJ-specific
(the `VimExTestCase` hierarchy accounts for 27) and to remain `jvmTest`. They must be
individually triaged in phase 2, not silently dropped.

### Chosen approach: headless host + `commonTest`

Build an in-memory implementation of `VimInjector`, re-implement `VimTestCase`'s method waist
against it, and relocate the suite to `commonTest`.

Alternatives considered and rejected:

- **Declarative corpus + differential testing.** Extract tests to data and replay on both
  targets. The 2,777 `doTest` call sites are close to this shape already, but the conversion
  is a large mechanical job and loses every test that is not declarative.
- **JVM suite plus thin JS smoke tests.** Cheapest, but verifies almost nothing precisely
  where JVM/JS divergence bites.

### Host implementation cost

The engine's 31 `*Base` classes already implement most service logic in-engine, so the
residual surface a host must supply is smaller than the ~50 injector services suggest:

| Interface | Members | Provided by base | Host must supply |
|---|---|---|---|
| `VimEditor` | 91 | 2 | ~89 |
| `VimCaret` | 35 | 9 | ~26 |
| `VimDocument` | 4 | — | 4 |
| ~48 other services | — | mostly thin over `*Base` | thin or empty |

`VimLookupManager`, `VimTemplateManager`, `VimPsiService`, `SpellcheckerService` and
`VimStatistics` are legitimate no-ops in a headless context.

### Why this host is not throwaway

It becomes the permanent CI harness for both targets, and the reference implementation the VS
Code host in sub-projects 2–3 is checked against — the same suite, a different host.

### What it deliberately does not prove

The headless host is **synchronous**. It does not exercise the sync-engine/async-document
mismatch that dominates sub-project 3. A green suite here means *the engine ported correctly*,
not *host integration works*. This distinction must not be blurred when reporting progress.

---

## 7. Sequencing and gates

The ordering exists to isolate one variable at a time.

### Phase 0 — antlr-kotlin spike *(gate)*

Timeboxed and throwaway. Generate Kotlin parsers from all three grammars; port
`BailErrorLexer` and `VimRegexParserErrorStrategy`; run the engine's existing regex and
Vimscript tests against them on the JVM. Also size the JUnit5 → `kotlin.test` migration (see
risks).

**Go/no-go for the Multiplatform approach.** If antlr-kotlin cannot carry these grammars, stop
and revisit the JVM sidecar rather than discovering the problem in month three.

### Phase 1 — Multiplatform scaffolding, `jvm` target only

Convert `vim-engine` and `api` to multiplatform. Everything that compiles multiplatform moves
to `commonMain`; everything else parks in `jvmMain`. No behavior changes, no deletions.

*Gate: IntelliJ plugin builds; all 653 test files green.*

### Phase 2 — Headless host and `VimTestCase` re-point

Build the in-memory host; re-implement the method waist against it. Triage the 55
non-`VimTestCase` files into "port" or "`jvmTest` permanently".

*Gate: the 598-file suite passes on **both** hosts — IntelliJ fixtures and headless — both on
the JVM.*

This double-green is the crux of the plan. It proves the headless host is behaviorally
equivalent to the real one **while the runtime is still constant**. When JS arrives in phase 4,
the host is already known-good, so any failure is unambiguously a JS-runtime divergence.
Without this phase, phase 4 becomes an open-ended "is it the host or the runtime?"
investigation.

### Phase 3 — Workstreams W2–W5

Neutral key type, de-reflection, JDK substitutions, coroutines and serialization. Each step
moves code from `jvmMain` into `commonMain`.

*Gate: the suite stays green on **both hosts** after every step.* Steps are individually
revertible.

### Phase 4 — Enable the `js` target

`commonMain` compiles for JS; KSP-generated JSON becomes embedded string constants; run the
headless suite under Node. Expect divergences in string and char handling, regex semantics,
number coercion, and collection iteration order.

*Gate: headless suite green on JS.*

### Phase 5 — CI and publish

Both targets in CI. Multiplatform artifact published for sub-project 2 to consume.

---

## 8. Risks

| Risk | Phase | Mitigation |
|---|---|---|
| antlr-kotlin cannot carry the grammars, particularly the error-strategy subclassing | 0 | Structured as a gate — fail in week one and revisit the sidecar approach |
| Headless host silently diverges from real IntelliJ behavior | 2 | The double-green gate is precisely this check |
| JS regex differs from JVM regex in Vim-visible ways | 4 | Exposure is limited because the engine has its own ANTLR-based regex engine; the risk is confined to `kotlin.text.Regex` substitutions introduced by W4 |
| Relocating 598 files from `src/test/java` to `commonTest` drags JUnit5-only features (`@Nested`, `@ParameterizedTest`) that `kotlin.test` lacks | 2 | Size during phase 0; quarantine stragglers as `jvmTest` |
| Concurrent collections replaced by plain ones mask a real JVM-side race | 3 | Confirm single-threaded-per-editor before substituting, do not assume |
| Test quarantine grows quietly until coverage is theatre | 2–4 | Track quarantined test count as an explicit reported metric, gate on it not growing between phases |

## 9. Open question, deliberately deferred

**When to drop the IntelliJ plugin.** As a hard fork it is eventually dead weight, but
throughout this sub-project it is the only *working* reference host and the source of the
double-green gate in phase 2.

Recommendation: keep it until sub-project 3 has the VS Code host passing the suite, then drop
it in one go. This is not settled by this spec and does not need to be settled to begin
implementation.
