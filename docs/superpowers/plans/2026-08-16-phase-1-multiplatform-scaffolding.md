# Phase 1 — Multiplatform scaffolding, `jvm` target only

**Spec:** `docs/superpowers/specs/2026-08-16-vim-engine-multiplatform-design.md` §7 phase 1
**Baseline:** `docs/superpowers/baselines/2026-08-16-phase-1-baseline.md`
**Predecessor:** `findings/2026-08-16-antlr-kotlin-gate.md` (phase 0, GO WITH CAVEATS)

## Goal

Convert `vim-engine` and `api` from `kotlin("jvm")` to `kotlin("multiplatform")` with **only**
the `jvm` target enabled. Everything that compiles multiplatform moves to `commonMain`;
everything else parks in `jvmMain`.

**No behavior changes. No deletions. No API changes.** This phase is a source-set
reorganisation and a build conversion, nothing else.

## Done-condition

1. `./gradlew build` succeeds; the IntelliJ plugin builds and `runIde` starts.
2. The standard suite reports **12,645 tests / 0 failures / 0 errors** — the same *count*, not
   merely the same pass rate. A dropped test count means tests stopped being discovered, which
   source-set moves cause silently.
3. Every one of the 8 dependent modules still compiles against `:vim-engine` / `:api`.
4. `git diff --stat` shows **zero** changes to `.kt` file *contents* outside of package/import
   lines and the build files. Moves, not edits.

## Two decisions taken before this plan

- **W1 (ANTLR → antlr-kotlin) is deferred out of phase 1.** The parser subpackage and the
  generated Java park in `jvmMain` and move later. Rationale: the spec's sequencing exists to
  isolate one variable at a time, and phase 2's double-green gate depends on phase 1 being
  behaviorally inert. Cost accepted: the parser subpackage moves twice.
- **Grammar-sharing policy (phase 0 caveat 1) resolved as: move actions out of the grammars.**
  Stateful lexer behavior leaves `@members`/inline actions and becomes an external
  listener/interceptor, so one `.g4` serves both builds with no target-language code in it.
  This is W1 work and **does not execute in phase 1** — recorded here because it constrains
  the W1 design and must not be re-litigated later.

## Global constraints

- Kotlin **2.3.20**, JDK **21** (`javaVersion=21`, `kotlinVersion=2.3.20` in `gradle.properties`).
- **`JAVA_HOME` must point at a JDK 21.** PATH `java` is 17 and the root build hard-fails at
  `build.gradle.kts:188` in ~4s before compiling anything. On this machine:
  `/Users/neshkeev/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home`
- Run gradle with `--console=plain`.
- MIT licence headers on any new file, matching the existing header block.
- Commit after each task. Never commit a red build except where a task explicitly says to.
- Do **not** modify `.g4` files, and do **not** change any Kotlin file's logic. Package
  declarations and import lines are the only permitted content edits.

---

## Task 1 — Gate: does the `antlr` plugin survive the KMP conversion?

**This is the plan's one real unknown and everything downstream assumes an answer.**

`vim-engine` applies the `antlr` plugin, which generates 12 Java files into
`build/generated-src/antlr/main` and wires them into the `java` plugin's `main` source set.
`kotlin("multiplatform")` has no `main` Java source set. Ten Kotlin files import the generated
package (`ScriptVisitor`, `ExecutableVisitor`, `ExpressionVisitor`, `CommandVisitor`,
`VimRegexParser`, `CollectionElementVisitor`, `PatternVisitor`, `MultiVisitor`,
`BailErrorLexer`, `VimscriptParserBase`).

**Steps**

1. On a scratch branch, convert **only** `vim-engine/build.gradle.kts` from `kotlin("jvm")` to
   `kotlin("multiplatform")` with `jvm()` as the sole target, leaving all sources at
   `src/main/kotlin` via an explicit `kotlin.sourceSets["jvmMain"].kotlin.srcDir("src/main/kotlin")`.
   Do not move a single file yet.
2. Wire the ANTLR output into the jvm target. Try, in order, stopping at the first that works:
   - (a) `kotlin.sourceSets["jvmMain"].kotlin.srcDir(tasks.generateGrammarSource)` plus Java
     compilation in the jvm target;
   - (b) keep the `java` plugin applied alongside KMP and let `generateGrammarSource` feed
     `sourceSets["main"].java`, consumed by `jvmMain`;
   - (c) extract the grammars and generated parsers into a new JVM-only module
     (`vim-engine-parser`) that `jvmMain` depends on.
3. Record for each attempt: what was tried, the exact failure, and whether it is fixable.
4. Run `./gradlew :vim-engine:compileKotlinJvm --console=plain`.
5. Run the full standard suite.

**Gate.** If (a) or (b) works, phase 1 proceeds as planned and the parser subpackage parks in
`jvmMain`. If only (c) works, **stop and report** — extracting a module is a structural change
the spec did not anticipate, and it changes tasks 3–6. Do not proceed to task 2 on option (c)
without a ruling.

**Deliverable:** a short findings note recording which option works and the evidence, committed
to `docs/superpowers/plans/`. Also record whether KSP still runs (`kspKotlin` becomes
`kspKotlinJvm` under KMP) and whether the four generated JSON resources still land in the jar.

---

## Task 2 — Convert `api` to Multiplatform

`api` is the small module (~10 files) and the low-risk rehearsal for task 3.

1. Convert `api/build.gradle.kts` to `kotlin("multiplatform")`, `jvm()` only.
2. `git mv api/src/main/kotlin` → `api/src/commonMain/kotlin`; `api/src/test/kotlin` →
   `api/src/commonTest/kotlin` **only if** the tests use no JVM-only API — otherwise
   `jvmTest`. Check before moving.
3. Move the `compileOnly` coroutines dependency into the `jvmMain` source-set dependency block.
4. Verify `annotation-processors`, `vim-engine` and the 6 other consumers still compile.

**Gate:** `./gradlew build -x test` green; full suite still 12,645 / 0.

---

## Task 3 — Convert `vim-engine` to Multiplatform, all sources still in `jvmMain`

Deliberately does **not** move anything to `commonMain` yet. Isolates the build conversion from
the source-set split.

1. Apply the option task 1 proved.
2. `git mv vim-engine/src/main/kotlin` → `vim-engine/src/jvmMain/kotlin`,
   `src/main/resources` → `src/jvmMain/resources`, `src/test` → `src/jvmTest`.
3. Update the KSP `generated_directory` arg to the new resources path. The four JSONs are
   **tracked in git** (`src/main/resources/ksp-generated/*.json`) — `git mv` them, do not
   regenerate-and-commit, so the diff shows a pure move.
4. Move the JVM-specific compiler arg `-Xjvm-default=all-compatibility` onto the jvm target's
   `compilerOptions`.
5. Rework `publishing`: `from(components["java"])` does not exist under KMP. Use the
   `kotlin` component. **Note the artifact coordinates change** (KMP appends `-jvm` to the
   platform artifact) — check whether anything depends on the published coordinate, and report
   if so rather than silently changing it.
6. `withSourcesJar()`/`withJavadocJar()` come from the `java` plugin; KMP publishes sources
   itself. Reconcile.

**Gate:** full suite 12,645 / 0. Plugin builds.

---

## Task 4 — Classify every `vim-engine` source file: common or jvm

Produce the move-list. **Analysis only — moves nothing.**

Classify all 823 `src/main` files. A file is **jvm-bound** if it references any of:

| Blocker | Known count | Resolved by |
|---|---|---|
| `com.maddyhome.idea.vim.parser.generated` (ANTLR) | 10 files | W1 (deferred) |
| `javax.swing.KeyStroke` | 55 files | W2 |
| `java.awt.event.KeyEvent` / `InputEvent` | 26 refs / 22 files | W2 |
| `kotlin.reflect.full.*` | 6 call sites, 2 files | W3 |
| `java.util.*` (real JVM types, not aliased) | ~110 imports, ~15 distinct types | W4 |
| Other JVM-only stdlib (`isJavaIdentifierPart`, `Locale`, `Pattern`, …) | ≥3 known | W4 |
| `kotlinx.serialization` / `kotlinx.coroutines` **-jvm** artifacts | 4 / 6 files | W5 |

Transitivity matters: a common file may not reference a jvm-bound type. Compute the closure,
do not classify file-by-file in isolation.

**Deliverable:** a committed move-list — every file, its destination, and for jvm-bound files
the specific blocker and the workstream that will free it. This list is the input to task 5
and the sizing input for phase 3.

**Report the count.** If `commonMain` comes out far below the spec's "~95% of today's engine",
say so — that is a finding about the spec, not a failure of the task.

---

## Task 5 — Execute the move to `commonMain`

Mechanical, driven by task 4's list. Move in **dependency order, innermost first**, compiling
between batches rather than at the end.

1. Batch the move-list into groups of ≤40 files by package.
2. Per batch: `git mv`, `./gradlew :vim-engine:compileKotlinJvm`, fix only package/import lines.
3. Any file that will not compile in `commonMain` goes **back to `jvmMain`** and is appended to
   the move-list with its blocker recorded. Do not add `expect`/`actual` to force it —
   that is phase 3 work.
4. Commit per batch, so a bad batch is revertible alone.

**Gate:** full suite 12,645 / 0 after every batch, not merely at the end.

---

## Task 6 — Verify consumers and the plugin

1. All 8 dependent modules compile: root, `ideavim-common`, `ideavim-frontend`,
   `ideavim-backend`, `ideavim-backend-bookmarks`, `ideavim-backend-bookmarks-core`,
   `annotation-processors`, plus `tests:java-tests`.
2. `./gradlew runIde` starts and IdeaVim loads. Manual smoke: normal-mode motion, `:` command,
   a `.ideavimrc` mapping.
3. Full standard suite: **12,645 / 0 / 0**, and confirm the count, not just the colour.
4. Confirm the four KSP JSONs are present in the built jar.

---

## Task 7 — Record the phase 1 result

A findings note: final `commonMain` vs `jvmMain` file counts against the spec's "~95%"
expectation; every file still in `jvmMain` grouped by blocking workstream (this is phase 3's
work-list); anything the spec got wrong; and the updated test-count baseline for phase 2.

---

## Risks

| Risk | Mitigation |
|---|---|
| ANTLR plugin cannot be wired into KMP | Task 1 is a gate specifically for this, with a stop-and-report on the structural option |
| KSP under KMP silently stops emitting the four JSONs | Task 1 checks explicitly; task 6 re-checks the jar |
| Published artifact coordinates change and break a consumer | Task 3 step 5 checks and reports rather than silently changing |
| `commonMain` ends up far smaller than "~95%" | Task 4 reports the real number; this is information for phase 3, not a phase 1 failure |
| A file compiles in `commonMain` but changes behavior | Prevented by the constraint that only package/import lines may change; the 12,645-test gate is the check |
| Test count silently drops as tests move source sets | The gate is on the count, not the pass rate |
