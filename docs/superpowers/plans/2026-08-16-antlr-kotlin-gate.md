# Phase 0: antlr-kotlin Go/No-Go Gate — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine, with evidence, whether `antlr-kotlin` can replace IdeaVim's Java ANTLR parsers on both `jvm` and `js` targets — and therefore whether the Kotlin Multiplatform approach to the VS Code port is viable.

**Architecture:** A standalone, throwaway Gradle build under `spike/antlr-kotlin/` that never touches the main build. It generates Kotlin parsers from IdeaVim's three existing `.g4` grammars, ports the ~1,406-line regex parser subpackage onto the Kotlin ANTLR runtime, and runs IdeaVim's *existing* regex parser tests against them on JVM and Node. Vimscript — which has almost no engine-local test coverage — is validated by differential testing against a corpus of 1,815 real commands extracted from the IntelliJ test suite.

**Tech Stack:** Kotlin 2.3.20 Multiplatform, Gradle 9.6.1, JDK 21, `antlr-kotlin` 1.0.13, Node.js (via the Kotlin/JS Gradle plugin).

**Spec:** `docs/superpowers/specs/2026-08-16-vim-engine-multiplatform-design.md` (section 5 W1, section 7 phase 0)

## Global Constraints

- **This phase is throwaway.** No code produced here is intended to ship. Everything lives under `spike/antlr-kotlin/` and is deleted when the gate resolves. Do not modify `vim-engine/`, `src/`, or the root `settings.gradle.kts`.
- **Kotlin version: 2.3.20** — must match `gradle.properties` `kotlinVersion` exactly, so results transfer to the real build.
- **JDK 21** — matches `gradle.properties` `javaVersion=21`.
- **antlr-kotlin version: 1.0.13** — Gradle plugin id `com.strumenta.antlr-kotlin`, runtime `com.strumenta:antlr-kotlin-runtime`. Both on the Gradle Plugin Portal and Maven Central as of 2026-08-12.
- **Runtime package rename:** the Kotlin runtime lives at `org.antlr.v4.kotlinruntime`, *not* `org.antlr.v4.runtime`. Every ported file needs its ANTLR imports rewritten.
- **Generated parser package:** keep `com.maddyhome.idea.vim.parser.generated` — identical to the main build, so ported sources need no other package edits.
- **Grammar sources are read-only.** Copy `.g4` files into the spike; never edit the originals in `vim-engine/src/main/antlr/`.
- **The gate is a written report, not working code.** Task 8 is the deliverable. A spike that produces beautiful code and no recommendation has failed.
- **Copyright header:** every new `.kt` file in this repo carries the standard IdeaVim MIT header. Copy it verbatim from any existing engine file.

---

## Background: what we already know

Measured from the repository at plan time. An executor does not need to re-derive these.

| Fact | Value |
|---|---|
| Grammars | `vim-engine/src/main/antlr/{RegexLexer.g4, RegexParser.g4, Vimscript.g4}` — 663 / 223 / 943 lines |
| Current ANTLR config | root `antlr` Gradle plugin, args `-package com.maddyhome.idea.vim.parser.generated -visitor` |
| Regex parser subpackage | `vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp/parser/` — 14 files, 1,406 lines |
| Runtime classes subclassed | `Lexer` (via `BailErrorLexer`), `DefaultErrorStrategy` (via `VimRegexParserErrorStrategy`) |
| Runtime classes imported | `CharStream`, `CharStreams`, `CommonTokenStream`, `ParserRuleContext`, `ParseTree`, `TerminalNode`, `Token`, `Parser`, `Recognizer`, `BaseErrorListener`, `RecognitionException`, `LexerNoViableAltException` |
| All of the above exist in antlr-kotlin | Verified — `DefaultErrorStrategy.kt`, `CharStreams.kt`, `LexerNoViableAltException.kt`, etc. all present in `org/antlr/v4/kotlinruntime` |
| Engine-local regex tests | `VimRegexParserTest` (397 lines), `VimRegexEngineTest` (1,786), `VimRegexTest` (458), `VimRegexTestUtils` (244) |
| Engine-local Vimscript parser tests | **Effectively none** — Vimscript is tested indirectly through 4,509 `enterCommand(…)` calls in the IntelliJ-bound suite |
| Documented antlr-kotlin limitation | "The Kotlin ANTLR runtime is not thread safe" |
| **Grammar-embedded Java actions** | **`RegexLexer.g4` only** — an `@members` block (lines 28–33) declaring `public Boolean ignoreCase = null;` and two Java methods, called from 8 inline lexer actions at lines 106, 107, 251, 252, 397, 398, 542, 543. `RegexParser.g4` and `Vimscript.g4` contain no actions. |

**On the embedded actions:** ANTLR copies `@members` and inline action bodies into the generated
parser *verbatim, in the target language*. Java syntax there will not compile as Kotlin. This
must be translated before any generated regex lexer compiles — see Task 2, steps 2–3. It is
bounded (one block, eight one-line call sites) but it is a hard blocker, not a warning.

**Deliberate expansion beyond the spec:** spec section 7 phase 0 scopes the gate to the JVM.
This plan also runs the ported tests on Node (Task 5). The reason is that JVM success would
leave the actual question — does this work where the extension runs? — unanswered, and the
marginal cost is one extra Gradle task once the Multiplatform scaffolding from Task 1 exists.

**Why `VimRegexParserTest` is the right gate test:** it depends only on `VimRegexParser` and `VimRegexParserResult`, and every assertion is `assertSuccess`/`assertFailure`. Failure paths run through `BailErrorLexer` and `VimRegexParserErrorStrategy` — exactly the error-strategy subclassing identified in the spec as the highest-risk divergence. It tests the risk directly rather than by proxy.

---

## File Structure

```
spike/antlr-kotlin/
  settings.gradle.kts                 standalone build; keeps the spike out of the main build
  build.gradle.kts                    KMP jvm+js, antlr-kotlin plugin, grammar generation
  antlr/
    RegexLexer.g4                     copied verbatim
    RegexParser.g4                    copied verbatim
    Vimscript.g4                      copied verbatim
  src/commonMain/kotlin/com/maddyhome/idea/vim/
    regexp/VimRegexErrors.kt          minimal stub — only the enum values the parser references
    regexp/parser/                    ported from vim-engine, ANTLR imports rewritten
  src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/
    VimRegexParserTest.kt             ported from vim-engine, JUnit5 -> kotlin.test
  src/commonTest/kotlin/.../vimscript/
    VimscriptCorpusTest.kt            differential harness over the extracted corpus
  corpus/
    extract-corpus.sh                 pulls literal commands out of src/test
    vimscript-corpus.txt              1,815 unique commands (generated, committed)
    vimscript-golden.txt              parse trees from the Java parser (generated, committed)
findings/
  2026-08-16-antlr-kotlin-gate.md     the go/no-go report — the actual deliverable
```

Rationale for the standalone build: the root `build.gradle.kts` enforces a JDK check and carries IntelliJ Platform plugin configuration that a KMP module has no use for. A nested build with its own `settings.gradle.kts` is invisible to `./gradlew` at the root, so the spike cannot break the main build, and deleting it later is `rm -rf`.

---

## Task 1: Standalone KMP build with jvm and js targets

Proves the Kotlin/JS toolchain works in this environment **before** ANTLR is involved. If Node or the JS toolchain is broken, we learn it here rather than misattributing it to antlr-kotlin.

**Files:**
- Create: `spike/antlr-kotlin/settings.gradle.kts`
- Create: `spike/antlr-kotlin/build.gradle.kts`
- Test: `spike/antlr-kotlin/src/commonTest/kotlin/SmokeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a Gradle build invokable as `./gradlew -p spike/antlr-kotlin <task>` with working `jvmTest` and `jsNodeTest` tasks

- [ ] **Step 1: Create the settings file**

```kotlin
// spike/antlr-kotlin/settings.gradle.kts
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "antlr-kotlin-spike"
```

- [ ] **Step 2: Create the build file**

Note: no ANTLR yet. One variable at a time.

```kotlin
// spike/antlr-kotlin/build.gradle.kts
plugins {
  kotlin("multiplatform") version "2.3.20"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(21)

  jvm()
  js {
    nodejs()
  }

  sourceSets {
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    jvmTest.dependencies {
      implementation(kotlin("test-junit5"))
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
```

- [ ] **Step 3: Write the failing smoke test**

```kotlin
// spike/antlr-kotlin/src/commonTest/kotlin/SmokeTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
  @Test
  fun `toolchain runs tests on this platform`() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun `this test is expected to fail on first run`() {
    assertEquals("js-or-jvm", "deliberate-failure")
  }
}
```

- [ ] **Step 4: Run both targets and confirm the deliberate failure appears on each**

```bash
./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest
```

Expected: BUILD FAILED, with `this test is expected to fail on first run` failing **twice** — once under `jvmTest`, once under `jsNodeTest`. Seeing it fail on both proves both test runners actually execute. If only one failure appears, one target is not running tests — fix that before continuing.

- [ ] **Step 5: Delete the deliberate failure**

Remove the `this test is expected to fail on first run` function entirely, leaving only the first test.

- [ ] **Step 6: Run both targets and confirm green**

```bash
./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add spike/antlr-kotlin
git commit -m "spike: standalone KMP build with jvm and js targets"
```

---

## Task 2: Generate Kotlin parsers from the regex grammars

**Files:**
- Create: `spike/antlr-kotlin/antlr/RegexLexer.g4` (copy of `vim-engine/src/main/antlr/RegexLexer.g4`)
- Create: `spike/antlr-kotlin/antlr/RegexParser.g4` (copy of `vim-engine/src/main/antlr/RegexParser.g4`)
- Modify: `spike/antlr-kotlin/build.gradle.kts`
- Test: `spike/antlr-kotlin/src/commonTest/kotlin/GeneratedParserSmokeTest.kt`

**Interfaces:**
- Consumes: the Gradle build from Task 1
- Produces: generated Kotlin classes `com.maddyhome.idea.vim.parser.generated.RegexLexer` and `RegexParser`, plus the visitor base class `RegexParserBaseVisitor<T>`, compiled into `commonMain` for both targets

- [ ] **Step 1: Copy the two regex grammars**

```bash
mkdir -p spike/antlr-kotlin/antlr
cp vim-engine/src/main/antlr/RegexLexer.g4 spike/antlr-kotlin/antlr/
cp vim-engine/src/main/antlr/RegexParser.g4 spike/antlr-kotlin/antlr/
```

`RegexParser.g4` stays byte-identical. `RegexLexer.g4` needs its embedded Java translated —
next step.

- [ ] **Step 2: Translate the `@members` block in the spike's `RegexLexer.g4` to Kotlin**

Replace lines 28–33 of `spike/antlr-kotlin/antlr/RegexLexer.g4`:

```
@members {
    public Boolean ignoreCase = null;

    void setIgnoreCase() { ignoreCase = true; }
    void setNoIgnoreCase() { if (ignoreCase == null) ignoreCase = false; }
}
```

with:

```
@members {
    var ignoreCase: Boolean? = null

    fun markIgnoreCase() { ignoreCase = true }
    fun markNoIgnoreCase() { if (ignoreCase == null) ignoreCase = false }
}
```

The functions are renamed because a Kotlin property `ignoreCase` already generates a
`setIgnoreCase` accessor; keeping the original names invites an accessor collision for no
benefit. The **property name stays `ignoreCase`** — `VimRegexParser.getCaseSensitivitySettings`
reads `lexer.ignoreCase` and that call site must keep working unchanged.

- [ ] **Step 3: Update the 8 inline actions to the renamed functions**

```bash
sed -i '' 's/{ setIgnoreCase(); }/{ markIgnoreCase() }/g; s/{ setNoIgnoreCase(); }/{ markNoIgnoreCase() }/g' \
  spike/antlr-kotlin/antlr/RegexLexer.g4
grep -c 'mark\(No\)\?IgnoreCase()' spike/antlr-kotlin/antlr/RegexLexer.g4
```

Expected: `8`. If it is not 8, inspect lines 106, 107, 251, 252, 397, 398, 542, 543 by hand —
note that line 543's token is named `OT_IGNORE_CASE_VNOMAGIC`, an apparent upstream typo for
`NOT_...`; leave it as-is, it is not our concern here.

**Record in findings:** grammars containing target-language actions cannot be shared verbatim
between the Java and Kotlin builds. Phase 1 must decide between maintaining two grammar copies
and moving this state out of the grammar entirely. This is a real, if small, cost of the
Multiplatform approach and belongs in the report regardless of the verdict.

- [ ] **Step 4: Add the antlr-kotlin plugin and generation task**

Add the import at the very top of the file, add the plugin, the runtime dependency, the task, and the source-set wiring.

```kotlin
// spike/antlr-kotlin/build.gradle.kts — additions
import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

plugins {
  kotlin("multiplatform") version "2.3.20"
  id("com.strumenta.antlr-kotlin") version "1.0.13"
}

// ... repositories and kotlin { } block from Task 1 ...

val generateKotlinGrammarSource = tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
  dependsOn("cleanGenerateKotlinGrammarSource")

  source = fileTree(layout.projectDirectory.dir("antlr")) {
    include("**/*.g4")
  }

  // Must match the main build so ported sources need no package edits
  packageName = "com.maddyhome.idea.vim.parser.generated"
  arguments = listOf("-visitor")

  val outDir = "generatedAntlr/${packageName!!.replace(".", "/")}"
  outputDirectory = layout.buildDirectory.dir(outDir).get().asFile
}
```

Inside the existing `kotlin { sourceSets { … } }` block, add the generated sources to `commonMain` and the runtime dependency:

```kotlin
    commonMain {
      kotlin {
        srcDir(generateKotlinGrammarSource)
      }
      dependencies {
        implementation("com.strumenta:antlr-kotlin-runtime:1.0.13")
      }
    }
```

- [ ] **Step 5: Run generation and confirm the parsers appear**

```bash
./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource
ls spike/antlr-kotlin/build/generatedAntlr/com/maddyhome/idea/vim/parser/generated/
```

Expected: files including `RegexLexer.kt`, `RegexParser.kt`, `RegexParserBaseVisitor.kt`, `RegexParserVisitor.kt`.

**If generation fails**, this is a *gate signal, not a blocker to work around*. Record the exact ANTLR error and which grammar construct caused it in `findings/2026-08-16-antlr-kotlin-gate.md`, then continue to Task 6 to assess Vimscript separately — a partial result is still a useful gate answer.

- [ ] **Step 6: Write a test that the generated parser is reachable and parses a trivial pattern**

```kotlin
// spike/antlr-kotlin/src/commonTest/kotlin/GeneratedParserSmokeTest.kt
import com.maddyhome.idea.vim.parser.generated.RegexLexer
import com.maddyhome.idea.vim.parser.generated.RegexParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertNotNull

class GeneratedParserSmokeTest {
  @Test
  fun `generated regex parser parses a literal pattern`() {
    val lexer = RegexLexer(CharStreams.fromString("abc"))
    val parser = RegexParser(CommonTokenStream(lexer))
    assertNotNull(parser.pattern())
  }
}
```

If the entry rule is not named `pattern`, read the first rule in `RegexParser.g4` and use that name instead.

- [ ] **Step 7: Run on both targets**

```bash
./gradlew -p spike/antlr-kotlin jvmTest jsNodeTest
```

Expected: PASS on both. This is the first real evidence that generated ANTLR code runs under Node.

- [ ] **Step 8: Commit**

```bash
git add spike/antlr-kotlin
git commit -m "spike: generate Kotlin regex parsers via antlr-kotlin"
```

---

## Task 3: Port the regex parser subpackage onto the Kotlin runtime

**Files:**
- Create: `spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/VimRegexErrors.kt` (minimal stub)
- Create: `spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp/parser/**` (ported from `vim-engine`, 14 files, ~1,406 lines)

**Interfaces:**
- Consumes: generated `RegexLexer`, `RegexParser`, `RegexParserBaseVisitor` from Task 2
- Produces: `VimRegexParser.parse(pattern: String): VimRegexParserResult`, where `VimRegexParserResult` is a sealed class with `Success` and `Failure` variants — the exact API `VimRegexParserTest` consumes in Task 4

- [ ] **Step 1: Copy the parser subpackage verbatim**

```bash
mkdir -p spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim
cp -R vim-engine/src/main/kotlin/com/maddyhome/idea/vim/regexp \
      spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/
```

Then delete everything under the copied `regexp/` **except** `parser/`. The NFA/engine half of the regex package is not needed for this gate and would drag in far more surface.

Verified safe: `BracketNormalizer` (called by `VimRegexParser.parse`) lives at
`regexp/parser/BracketNormalizer.kt`, and `CaseSensitivitySettings` is declared inside
`VimRegexParserResult.kt`. Both survive this deletion.

```bash
cd spike/antlr-kotlin/src/commonMain/kotlin/com/maddyhome/idea/vim/regexp
find . -maxdepth 1 -type f -name '*.kt' -delete
find . -maxdepth 1 -type d ! -name . ! -name parser -exec rm -rf {} +
cd -
```

- [ ] **Step 2: Rewrite the ANTLR imports**

```bash
find spike/antlr-kotlin/src/commonMain -name '*.kt' \
  -exec sed -i '' 's/org\.antlr\.v4\.runtime/org.antlr.v4.kotlinruntime/g' {} +
```

(`sed -i ''` is the macOS form; on Linux use `sed -i`.)

- [ ] **Step 3: Create the minimal `VimRegexErrors` stub**

The parser references error codes but the full enum lives in the deleted engine half. Read which values the `parser/` sources actually reference, then write a stub containing only those. At minimum it needs `E383`, used by both error classes:

```kotlin
/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.regexp

/**
 * Spike stub. Only the values referenced by the regex parser subpackage.
 * The real enum lives in vim-engine and is not needed for this gate.
 */
enum class VimRegexErrors {
  E383,
  ;
}
```

Find the full set with:

```bash
grep -rho 'VimRegexErrors\.[A-Z0-9]*' spike/antlr-kotlin/src/commonMain | sort -u
```

Add every value that appears.

- [ ] **Step 4: Compile both targets and fix what breaks**

```bash
./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs
```

Expected on first run: FAIL. Likely causes, in order of probability:

1. Residual references to deleted engine classes → delete the referencing file if it is engine-side, or stub it as in Step 3.
2. Signature drift between the Java and Kotlin runtimes — e.g. nullability of `Parser?` in `DefaultErrorStrategy.recover`. **Record every such difference**; these are the divergences the gate exists to measure.
3. JVM-only APIs in the copied sources.

Fix compilation, and log each fix in `findings/2026-08-16-antlr-kotlin-gate.md` under "porting friction". The log matters more than the code.

- [ ] **Step 5: Confirm both targets compile**

```bash
./gradlew -p spike/antlr-kotlin compileKotlinJvm compileKotlinJs
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add spike/antlr-kotlin findings
git commit -m "spike: port regex parser subpackage to Kotlin ANTLR runtime"
```

---

## Task 4: Run IdeaVim's regex parser tests on the JVM

The behavioral gate for error-strategy fidelity.

**Files:**
- Create: `spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/VimRegexParserTest.kt` (ported from `vim-engine/src/test/.../VimRegexParserTest.kt`, 397 lines)

**Interfaces:**
- Consumes: `VimRegexParser.parse(pattern)` and `VimRegexParserResult` from Task 3
- Produces: a pass/fail count that becomes evidence in the Task 8 report

- [ ] **Step 1: Copy the test verbatim**

```bash
mkdir -p spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal
cp vim-engine/src/test/kotlin/com/maddyhome/idea/vim/regexp/internal/VimRegexParserTest.kt \
   spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/
```

- [ ] **Step 2: Convert JUnit5 imports to kotlin.test**

The file imports `org.junit.jupiter.api.Test` and already imports `kotlin.test.fail`.

```bash
sed -i '' 's/^import org\.junit\.jupiter\.api\.Test$/import kotlin.test.Test/' \
  spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/regexp/internal/VimRegexParserTest.kt
```

**Record this**: a one-line import swap is the cheap case. Note in findings whether any JUnit5-only construct (`@Nested`, `@ParameterizedTest`, `@BeforeEach`) appears — it does not in this file, which is a positive signal for the phase 2 migration estimated in Task 7.

- [ ] **Step 3: Run on the JVM**

```bash
./gradlew -p spike/antlr-kotlin jvmTest --tests '*VimRegexParserTest*'
```

Expected: some result. **Do not assume PASS.** Record the exact pass/fail counts.

- [ ] **Step 4: Triage every failure**

For each failing test, classify it in the findings file as one of:

- **Grammar divergence** — antlr-kotlin generates a parser that accepts/rejects differently. *Serious; counts against the gate.*
- **Error-strategy divergence** — the pattern parses but the failure path behaves differently. *The predicted risk; counts against the gate.*
- **Spike artifact** — caused by the stubbing in Task 3, not by antlr-kotlin. *Does not count against the gate; note and move on.*

Fix only spike artifacts. **Do not fix genuine divergences** — they are the measurement.

- [ ] **Step 5: Commit**

```bash
git add spike/antlr-kotlin findings
git commit -m "spike: run VimRegexParserTest against Kotlin parsers on JVM"
```

---

## Task 5: Run the same tests on Node

The actual approach-B gate. Task 4 proves the grammar ports; this proves it *runs where the extension will run*.

**Files:**
- Modify: `findings/2026-08-16-antlr-kotlin-gate.md`

**Interfaces:**
- Consumes: the `commonTest` suite from Task 4
- Produces: a JVM-vs-JS comparison table for the report

- [ ] **Step 1: Run on Node**

```bash
./gradlew -p spike/antlr-kotlin jsNodeTest
```

- [ ] **Step 2: Compare against the JVM result**

Produce a table in the findings file:

| Test | JVM | JS | Divergence? |
|---|---|---|---|

Any test passing on JVM but failing on JS is the single most important finding in this phase — it means the *runtime*, not the grammar, diverges. Record the failure message verbatim.

- [ ] **Step 3: Measure JS parse throughput**

Rough numbers are fine; we need an order of magnitude, not a benchmark.

```kotlin
// append to VimRegexParserTest.kt, or a sibling file in commonTest
import kotlin.test.Test
import kotlin.time.measureTime

class ParsePerformanceProbe {
  @Test
  fun `parse a representative pattern 1000 times`() {
    val pattern = "\\(foo\\|bar\\)\\{2,5}[a-z]*\\$"
    val elapsed = measureTime {
      repeat(1000) { VimRegexParser.parse(pattern) }
    }
    println("1000 parses took $elapsed")
  }
}
```

Run on both targets and record both numbers. A JS/JVM ratio above roughly 10x on parsing is worth flagging in the report, since search-as-you-type recompiles patterns on every keystroke.

- [ ] **Step 4: Commit**

```bash
git add spike/antlr-kotlin findings
git commit -m "spike: run regex parser tests on Node, record JVM/JS comparison"
```

---

## Task 6: Vimscript grammar — differential testing against a real corpus

Vimscript has essentially no engine-local test coverage, so correctness is established by comparing the new parser against the current one on real input.

**Files:**
- Create: `spike/antlr-kotlin/antlr/Vimscript.g4` (copy)
- Create: `spike/antlr-kotlin/corpus/extract-corpus.sh`
- Create: `spike/antlr-kotlin/corpus/vimscript-corpus.txt` (generated, committed)
- Create: `spike/antlr-kotlin/corpus/vimscript-golden.txt` (generated, committed)
- Create: `spike/antlr-kotlin/src/commonTest/kotlin/com/maddyhome/idea/vim/vimscript/VimscriptCorpusTest.kt`

**Interfaces:**
- Consumes: generated `VimscriptLexer` / `VimscriptParser` from the copied grammar
- Produces: a divergence count between Java-generated and Kotlin-generated parse trees over 1,815 commands

- [ ] **Step 1: Copy the grammar and confirm it generates**

```bash
cp vim-engine/src/main/antlr/Vimscript.g4 spike/antlr-kotlin/antlr/
./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource
ls spike/antlr-kotlin/build/generatedAntlr/com/maddyhome/idea/vim/parser/generated/ | grep -i vimscript
```

Expected: `VimscriptLexer.kt`, `VimscriptParser.kt`, `VimscriptBaseVisitor.kt`. `Vimscript.g4` is the largest grammar at 943 lines; if generation fails here but succeeded for regex, that is a major gate finding — record the error verbatim.

- [ ] **Step 2: Write the corpus extraction script**

Kotlin source escapes must be unwound, and interpolated strings dropped — `set mouse=$mouse` is not a literal command.

```bash
#!/usr/bin/env bash
# spike/antlr-kotlin/corpus/extract-corpus.sh
# Extracts literal Vimscript commands from the IntelliJ-bound test suite.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

grep -rhoE 'enterCommand\("([^"\\]|\\.)*"\)' "$REPO_ROOT/src/test" --include=*.kt \
  | sed -E 's/^enterCommand\("//; s/"\)$//' \
  | grep -v '\$' \
  | python3 -c '
import sys
for line in sys.stdin:
    line = line.rstrip("\n")
    # Unwind Kotlin string escapes: \\ -> \, \" -> ", \t, \n
    out, i = [], 0
    while i < len(line):
        if line[i] == "\\" and i + 1 < len(line):
            nxt = line[i+1]
            # NB: keys must be ONE character — `nxt` is a single char. A "\\\\" key here
            # is a two-char string that can never match, silently leaving doubled
            # backslashes uncollapsed (this bug hit 24/1865 corpus lines when first run).
            out.append({"\\": "\\", "\"": "\"", "t": "\t", "n": "\n", "$": "$"}.get(nxt, "\\" + nxt))
            i += 2
        else:
            out.append(line[i]); i += 1
    joined = "".join(out)
    if joined.strip() and "\n" not in joined:
        print(joined)
' \
  | sort -u
```

- [ ] **Step 3: Generate the corpus**

```bash
chmod +x spike/antlr-kotlin/corpus/extract-corpus.sh
spike/antlr-kotlin/corpus/extract-corpus.sh > spike/antlr-kotlin/corpus/vimscript-corpus.txt
wc -l spike/antlr-kotlin/corpus/vimscript-corpus.txt
```

Expected: on the order of 1,700–1,815 lines. The drop from 1,815 is the `$`-interpolation filter. If the count is far lower, the escape handling is wrong — inspect and fix before continuing.

- [ ] **Step 4: Produce the golden file from the *existing Java* parser**

Add a temporary test in the main build that dumps normalized parse trees. `toStringTree` gives a comparable s-expression from both runtimes.

```kotlin
// vim-engine/src/test/kotlin/com/maddyhome/idea/vim/spike/GoldenDumpTest.kt  (TEMPORARY)
package com.maddyhome.idea.vim.spike

import com.maddyhome.idea.vim.parser.generated.VimscriptLexer
import com.maddyhome.idea.vim.parser.generated.VimscriptParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test

class GoldenDumpTest {
  @Test
  fun `dump parse trees for the corpus`() {
    val corpus = Path.of("../spike/antlr-kotlin/corpus/vimscript-corpus.txt")
    val out = StringBuilder()
    for (line in corpus.readLines()) {
      val tree = runCatching {
        val lexer = VimscriptLexer(CharStreams.fromString(line + "\n"))
        val parser = VimscriptParser(CommonTokenStream(lexer))
        parser.script().toStringTree(parser)
      }.getOrElse { "PARSE_ERROR: ${it::class.simpleName}" }
      out.append(line).append('\t').append(tree).append('\n')
    }
    Path.of("../spike/antlr-kotlin/corpus/vimscript-golden.txt").writeText(out.toString())
  }
}
```

If `script` is not the Vimscript entry rule, read the first rule in `Vimscript.g4` and use that name. Run it:

```bash
./gradlew :vim-engine:test --tests '*GoldenDumpTest*' --console=plain
```

Then **delete the temporary test file** — the main build must end this phase unmodified.

- [ ] **Step 5: Write the differential test**

Reading the corpus from disk in `commonTest` is awkward on JS. Simplest reliable approach: have the test read the golden file on JVM only, and run the JS comparison via a generated Kotlin constant. For the gate, running the differential on **JVM is sufficient** — Task 5 already establishes JVM/JS runtime parity independently.

```kotlin
// spike/antlr-kotlin/src/jvmTest/kotlin/com/maddyhome/idea/vim/vimscript/VimscriptCorpusTest.kt
package com.maddyhome.idea.vim.vimscript

import com.maddyhome.idea.vim.parser.generated.VimscriptLexer
import com.maddyhome.idea.vim.parser.generated.VimscriptParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test

class VimscriptCorpusTest {
  @Test
  fun `kotlin parser matches java parser across the corpus`() {
    val golden = Path.of("corpus/vimscript-golden.txt").readLines()
      .map { it.split('\t', limit = 2) }
      .filter { it.size == 2 }

    val divergences = mutableListOf<String>()
    for ((input, expected) in golden) {
      val actual = runCatching {
        val lexer = VimscriptLexer(CharStreams.fromString(input + "\n"))
        val parser = VimscriptParser(CommonTokenStream(lexer))
        parser.script().toStringTree(parser)
      }.getOrElse { "PARSE_ERROR: ${it::class.simpleName}" }

      if (actual != expected) {
        divergences += "INPUT: $input\n  java:   $expected\n  kotlin: $actual"
      }
    }

    println("corpus size: ${golden.size}, divergences: ${divergences.size}")
    divergences.take(50).forEach(::println)
    // Deliberately does not assert. This task measures; it does not gate.
  }
}
```

Note: this test **prints rather than asserts**. Its job is to produce a divergence count for the report, not to pass.

- [ ] **Step 6: Run it and record the divergence count**

```bash
./gradlew -p spike/antlr-kotlin jvmTest --tests '*VimscriptCorpusTest*' --console=plain
```

Record in findings: corpus size, divergence count, divergence rate, and the first 10 divergences verbatim. Classify each as grammar divergence, error-reporting difference, or corpus-extraction artifact.

- [ ] **Step 7: Commit**

```bash
git add spike/antlr-kotlin findings
git commit -m "spike: differential-test Vimscript grammar over 1815-command corpus"
```

---

## Task 7: Size the JUnit5 to kotlin.test migration

The spec lists this as a phase 0 sizing task and a named phase 2 risk. It is pure measurement — no production code.

**Files:**
- Modify: `findings/2026-08-16-antlr-kotlin-gate.md`

**Interfaces:**
- Consumes: nothing
- Produces: a count of test files using JUnit5-only constructs, feeding the phase 2 estimate

- [ ] **Step 1: Count JUnit5-only constructs across the behavioral suite**

```bash
echo "files in src/test:            $(find src/test -name '*.kt' | wc -l)"
echo "extending VimTestCase:        $(grep -rl 'VimTestCase' src/test --include=*.kt | wc -l)"
for c in '@Nested' '@ParameterizedTest' '@BeforeEach' '@AfterEach' '@TestFactory' \
         '@RepeatedTest' '@Disabled' '@ValueSource' '@MethodSource' '@CsvSource' '@TestTemplate'; do
  printf '%-22s %s\n' "$c" "$(grep -rl -- "$c" src/test --include=*.kt | wc -l)"
done
```

- [ ] **Step 2: Identify which have no kotlin.test equivalent**

`kotlin.test` provides `@Test`, `@BeforeTest`, `@AfterTest`, `@Ignore`. It has **no** equivalent for `@Nested`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, or the `@*Source` family. Those files either need restructuring or stay in `jvmTest`.

Record the union count — files using at least one unsupported construct — as the phase 2 quarantine floor.

- [ ] **Step 3: Sample three affected files and estimate per-file effort**

Pick three files using `@ParameterizedTest` or `@Nested`, read them, and note in findings whether conversion is mechanical (a loop replacing a parameterized test) or structural. Give a per-file effort band, not a total.

- [ ] **Step 4: Commit**

```bash
git add findings
git commit -m "spike: size JUnit5 to kotlin.test migration for phase 2"
```

---

## Task 8: Write the go/no-go report

**The deliverable.** Everything before this was evidence gathering.

**Files:**
- Create: `findings/2026-08-16-antlr-kotlin-gate.md` (consolidating notes accumulated in Tasks 3–7)

**Interfaces:**
- Consumes: all measurements from Tasks 2–7
- Produces: a recommendation that determines whether phases 1–5 proceed as specified, or the sidecar approach is revisited

- [ ] **Step 1: Write the report against this structure**

```markdown
# Phase 0 finding: antlr-kotlin viability

**Date:** <date>
**Question:** Can antlr-kotlin replace IdeaVim's Java ANTLR parsers on jvm and js?
**Recommendation:** GO / GO WITH CAVEATS / NO-GO

## Evidence

| Check | Result |
|---|---|
| Regex grammars generate | |
| Vimscript grammar generates | |
| Parser subpackage compiles (jvm) | |
| Parser subpackage compiles (js) | |
| VimRegexParserTest on JVM | N/397 assertions passing |
| VimRegexParserTest on Node | N/397 assertions passing |
| Vimscript corpus divergences | N / <corpus size> |
| JS:JVM parse time ratio | Nx |
| JUnit5-only test files | N of 653 |

## Porting friction
<every fix required in Task 3, with cause>

## Divergences found
<classified: grammar / error-strategy / runtime>

## Recommendation
<GO, GO WITH CAVEATS, or NO-GO — with reasoning>

## If NO-GO
<what specifically failed, and whether it is fixable within antlr-kotlin
or requires revisiting the JVM sidecar approach from the spec>
```

- [ ] **Step 2: Apply the decision rule**

State explicitly which of these the evidence supports. Do not soften a NO-GO to avoid discarding the work — a cheap NO-GO here is the entire point of the phase.

- **GO** — both grammars generate; all `VimRegexParserTest` assertions pass on both targets; corpus divergence rate under ~1% and every divergence explained.
- **GO WITH CAVEATS** — the above holds except for a bounded, enumerated set of divergences with known workarounds. List each caveat as a task to fold into phase 1.
- **NO-GO** — a grammar fails to generate, error-strategy behavior cannot be reproduced, JVM/JS divergences are unexplained, or the corpus divergence rate is high enough that Vimscript compatibility is in question. Recommend revisiting the JVM sidecar approach in spec section 1.

- [ ] **Step 3: Commit**

```bash
git add findings
git commit -m "spike: antlr-kotlin gate findings and recommendation"
```

- [ ] **Step 4: Stop and report to the user**

Do not proceed to phase 1. The gate's outcome determines whether the phase 1–5 plan gets written at all, and that is the user's decision.

---

## After this plan

- **On GO / GO WITH CAVEATS:** delete `spike/antlr-kotlin/` (keep `findings/`), then write the phase 1–5 implementation plan, folding any caveats into phase 1.
- **On NO-GO:** return to the spec's section 1 and reopen the hosting decision. The measurements here — particularly the corpus harness and the JUnit5 sizing — remain valid inputs for the sidecar approach.
