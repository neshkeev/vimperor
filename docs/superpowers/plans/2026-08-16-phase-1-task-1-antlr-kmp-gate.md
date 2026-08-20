# Phase 1 Task 1 — ANTLR under Kotlin Multiplatform: gate result

**Plan:** `docs/superpowers/plans/2026-08-16-phase-1-multiplatform-scaffolding.md` task 1
**Branch:** `spike/phase1-antlr-kmp-gate`
**Base:** `9495fcf1c`

**Verdict: GO — via option (a′), which the plan did not enumerate.** The structural
module split (option c) is **not** required.

---

## 1. The plan's options (a) and (b) are both impossible

Both assumed the `java` plugin could coexist with `kotlin("multiplatform")`. It cannot. The
`antlr` Gradle plugin applies `java-library` internally, and KGP hard-errors:

```
e: ❌ 'java' Plugin Incompatible with 'org.jetbrains.kotlin.multiplatform' Plugin
'java' Gradle plugin is not compatible with 'org.jetbrains.kotlin.multiplatform' plugin.
Solution: Consider adding a new subproject with 'java' plugin where the KMP project is
added as a dependency.

e: ❌ 'java-library' Plugin Incompatible with 'org.jetbrains.kotlin.multiplatform' Plugin
```

This is an error, not a warning; the build fails at configuration time. The toolchain's own
recommendation is option (c).

## 2. The question the plan did not ask

> Does a KMP `jvm` target compile Java **at all**, without the `java` plugin?

**Yes.** Probed in `:api` (small, no ANTLR entanglement) by dropping a trivial
`src/jvmMain/java/probe/JavaProbe.java` into a KMP-converted module with no `java` plugin and
no `withJava()`:

```
BUILD SUCCESSFUL
api/build/classes/java/jvmMain/probe/JavaProbe.class    <- produced
```

Kotlin 2.3.20 creates `compileJvmMainJava` / `compileJvmTestJava` for the JVM target by
default. `withJava()` is no longer needed (and is deprecated).

That makes option (a′) available:

> **Drop the ANTLR *plugin*; keep the ANTLR *tool*.** Invoke `org.antlr.v4.Tool` from a plain
> `JavaExec` task and feed the generated Java into the jvm target's own `compileJvmMainJava`.

## 3. Result on `vim-engine`

| Check | Result |
|---|---|
| `:vim-engine:compileKotlinJvm` | **BUILD SUCCESSFUL** |
| Generated ANTLR Java | 12 files in `build/generated-src/antlr/jvmMain/` |
| Generated Java compiled | 279 classes in `build/classes/java/jvmMain/`, incl. `RegexLexer.class` in `com/maddyhome/idea/vim/parser/generated/` |
| KSP under KMP | Works. All four JSONs regenerated **byte-identical to what is in git** |
| Full standard suite | **12,645 tests / 0 failures / 0 errors / 99 skipped — exact baseline match** |
| `buildPlugin` | **BUILD SUCCESSFUL**, 7.8 MB ZIP |
| Engine sources bundled in plugin ZIP | 1,292 entries, incl. `VimEditor.kt` |
| All 8 dependent modules | Compile against the KMP variants |

`api` was converted too (it is task 2's work, and was the probe vehicle). It has **zero tests**
— its JUnit/BOM dependencies were dead weight and are gone.

## 4. Six things that had to change, none of them in the plan

1. **`antlr4-runtime` must move `runtimeOnly` → `implementation`.** The `antlr` plugin put the
   runtime on the compile classpath via its own `antlr` configuration. Without the plugin, the
   engine's own `org.antlr.v4.runtime.*` imports fail to resolve. 40+ unresolved-reference
   errors until fixed.
2. **Kotlin needs the generated Java on its *source path*, separately from compiling it.**
   `compileJvmMainJava` compiles it; `jvmMain.kotlin.srcDir(antlrOutputDir)` is additionally
   required for Kotlin to *resolve* `com.maddyhome.idea.vim.parser.generated.*`.
3. **Grammar generation is ordered.** `RegexParser.g4` declares
   `options { tokenVocab=RegexLexer; }`, so `RegexLexer.tokens` must exist first. Implemented
   as two `JavaExec` tasks, the second passing `-lib` at the first's output directory.
4. **`ksp(...)` → `kspJvm(...)`**, and KSP registers its tasks lazily — `tasks.named("kspKotlinJvm")`
   fails at configuration time with "Task with name 'kspKotlinJvm' not found".
   `tasks.matching { it.name == … }.configureEach` works. KSP also needs an explicit
   `dependsOn(generateGrammarSource)` or Gradle reports an implicit-dependency violation.
5. **Configuration cache rejects `CommandLineArgumentProvider { … }` lambdas** in a build script
   ("cannot serialize Gradle script object references"). Plain `args(...)` resolved at
   configuration time works.
6. **`sourcesJarArtifacts` must be reconstructed by hand.** This is the one that would have
   bitten later: the root project bundles the engine's sources into the plugin ZIP by consuming
   a custom `sourcesJarArtifacts` configuration from `:vim-engine`
   (root `build.gradle.kts:132`, `moduleSources`). It came from the `java` plugin's
   `withSourcesJar()`. Removing the `java` plugin deletes it, and:

   > **`./gradlew test` still passes. `./gradlew buildPlugin` fails.**

   ```
   Could not resolve project ':vim-engine'.
     > A dependency was declared on configuration 'sourcesJarArtifacts' ... but no variant
       with that configuration name exists.
   ```

   **The test suite is not a sufficient gate for phase 1.** `buildPlugin` must be run too. The
   plan's done-condition already says "IntelliJ plugin builds" — this is concrete evidence for
   why that clause is load-bearing rather than ceremonial.

## 5. Known differences from master, not yet resolved

- **Bundled sources-jar layout changed.** Engine sources now appear under a `jvmMain/` prefix
  (`jvmMain/com/maddyhome/idea/vim/api/VimEditor.kt`) instead of the flat package path. Cosmetic
  for humans, but anything parsing that structure would notice. Not investigated.
- **Publishing is rewritten but unverified.** `from(components["java"])` does not exist under
  KMP; the block now configures KMP's own publications. **The published artifact coordinates
  change** — KMP appends `-jvm` to the platform artifact and adds a root `*-kotlin-metadata`
  module. Nothing was published or resolved to prove consumers survive this. Still open, as
  the plan's task 3 step 5 anticipated.
- **`withJavadocJar()` is gone** and was not replaced.
- **`metadataSourcesJar` / `sourcesJar` (kotlin target)** now also exist; only `jvmSourcesJar`
  is wired to `sourcesJarArtifacts`.

## 6. Consequences for the rest of phase 1

- **Tasks 2 and 3's build conversion is effectively already done** on this branch — `api` and
  `vim-engine` are both KMP with the `jvm` target. What remains of task 3 is the *source moves*
  (`src/main/kotlin` → `src/jvmMain/kotlin`, resources, tests) plus the publishing question.
  The `kotlin.srcDir("src/main/kotlin")` lines exist precisely so nothing had to move yet.
- **Add `buildPlugin` to every gate from here on**, alongside the 12,645-test check.
- **The plan's option (c) can be struck.** No JVM-only parser subproject is needed.
- **This does not touch W1.** The engine still uses Java ANTLR and the `org.antlr.v4.runtime`
  Java runtime, exactly as the deferral intended. The parser subpackage stays in `jvmMain`.
