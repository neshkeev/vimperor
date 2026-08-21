/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// PHASE 1 TASK 1 GATE - attempt (a'), not in the original plan.
//
// Attempts (a) and (b) are both dead: the `antlr` Gradle plugin applies
// `java-library`, and the Kotlin Multiplatform plugin hard-errors on that
// combination ("'java' Plugin Incompatible with ... multiplatform").
//
// (a') drops the `antlr` PLUGIN but keeps the ANTLR TOOL, invoked via JavaExec,
// feeding generated Java into the KMP jvm target's own `compileJvmMainJava`
// task. Verified separately in :api that a KMP jvm target compiles Java with no
// `java` plugin and no withJava() - Kotlin 2.3.20 does this by default.
//
// This avoids option (c) (a structural JVM-only parser subproject).

plugins {
    kotlin("multiplatform")
//    id("org.jlleitschuh.gradle.ktlint")
    id("com.google.devtools.ksp") version "2.3.7"
    kotlin("plugin.serialization") version "2.3.20"
    `maven-publish`
}

val kotlinVersion: String by project
val kotlinxSerializationVersion: String by project

// The root project bundles the engine's sources into the plugin ZIP by
// consuming this configuration (build.gradle.kts:132, `moduleSources`). It came
// from the `java` plugin's withSourcesJar() before; KMP names its own task
// `jvmSourcesJar`. Dropping this breaks `buildPlugin` but NOT `test`.
val sourcesJarArtifacts by configurations.registering {
  isCanBeConsumed = true
  isCanBeResolved = false
  attributes {
    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
  }
}

repositories {
    maven { url = uri("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2") }
}

// --- ANTLR without the antlr plugin

val antlrTool by configurations.registering

val antlrOutputDir = layout.buildDirectory.dir("generated-src/antlr/jvmMain")
val antlrSrcDir = layout.projectDirectory.dir("antlr")

// RegexParser.g4 declares `options { tokenVocab=RegexLexer; }`, so RegexLexer
// must be generated first to produce RegexLexer.tokens. Two ordered steps.
val generateLexerGrammars by tasks.registering(JavaExec::class) {
  classpath = files(antlrTool)
  mainClass.set("org.antlr.v4.Tool")
  inputs.files(antlrSrcDir.file("RegexLexer.g4"), antlrSrcDir.file("Vimscript.g4"))
  outputs.dir(antlrOutputDir)
  args(
    "-package", "com.maddyhome.idea.vim.parser.generated",
    "-visitor",
    "-o", antlrOutputDir.get().asFile.absolutePath,
    antlrSrcDir.file("RegexLexer.g4").asFile.absolutePath,
    antlrSrcDir.file("Vimscript.g4").asFile.absolutePath,
  )
}

val generateParserGrammars by tasks.registering(JavaExec::class) {
  dependsOn(generateLexerGrammars)
  classpath = files(antlrTool)
  mainClass.set("org.antlr.v4.Tool")
  inputs.files(antlrSrcDir.file("RegexParser.g4"))
  outputs.dir(antlrOutputDir)
  args(
    "-package", "com.maddyhome.idea.vim.parser.generated",
    "-visitor",
    // -lib is where tokenVocab looks for RegexLexer.tokens
    "-lib", antlrOutputDir.get().asFile.absolutePath,
    "-o", antlrOutputDir.get().asFile.absolutePath,
    antlrSrcDir.file("RegexParser.g4").asFile.absolutePath,
  )
}

val generateGrammarSource by tasks.registering {
  dependsOn(generateLexerGrammars, generateParserGrammars)
}

ksp {
  arg("generated_directory", "$projectDir/src/jvmMain/resources/ksp-generated")
  arg("vimscript_functions_file", "engine_vimscript_functions.json")
  arg("ex_commands_file", "engine_ex_commands.json")
  arg("commands_file", "engine_commands.json")
  arg("extensions_file", "ideavim_extensions.json")
}

kotlin {
  jvm()
  // No `js` target yet - deliberately. The probe recorded in
  // docs/superpowers/findings/2026-08-21-phase-4-js-probe.md compiles 729 of commonMain's 798
  // files; enabling it here would leave `./gradlew build` red until the remaining 69 are done.

  compilerOptions {
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
  }

  sourceSets {
    val commonMain by getting {
      // Phase 1 task 5. Only files with no JVM-API dependency live here; the
      // move-list is docs/superpowers/plans/2026-08-16-phase-1-task-4-move-list.tsv.
      dependencies {
        // :api is multiplatform as of W6, so common code can depend on it. `implementation`
        // rather than `api`, matching the visibility this had when it sat in jvmMain.
        implementation(project(":api"))
        implementation(project(":vim-annotations"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      }
    }
    val jvmMain by getting {
      // src/jvmMain/{kotlin,resources} are KMP defaults - no srcDir needed.
      // Kotlin needs the generated ANTLR Java on its source path to RESOLVE it
      // (it does not compile it - compileJvmMainJava does that, below).
      kotlin.srcDir(antlrOutputDir)
      dependencies {
        // Was runtimeOnly under the antlr plugin, which put the runtime on the
        // compile path via its own `antlr` configuration. Without the plugin the
        // engine's own `org.antlr.v4.runtime.*` imports need it at compile time.
        implementation("org.antlr:antlr4-runtime:4.13.2")
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly(project(":annotation-processors"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$kotlinxSerializationVersion")
        compileOnly(kotlin("reflect"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation("org.junit.jupiter:junit-jupiter-api:6.0.0")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.0")
        runtimeOnly("org.junit.vintage:junit-vintage-engine:6.1.2")
        implementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
        implementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
      }
    }
  }

  targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions {
          freeCompilerArgs.add("-Xjvm-default=all-compatibility")
        }
      }
    }
  }
}

dependencies {
  antlrTool("org.antlr:antlr4:4.13.2")
  add("kspJvm", project(":annotation-processors"))
}

// The generated ANTLR Java feeds the jvm target's own Java compilation, and the
// Kotlin compilation needs it on the source path too for resolution.
tasks.named<JavaCompile>("compileJvmMainJava") {
  dependsOn(generateGrammarSource)
  source(antlrOutputDir)
}

tasks.named("compileKotlinJvm") {
  dependsOn(generateGrammarSource)
}

// KSP's tasks are registered lazily by the plugin, so match rather than name().
// Mirrors the original `afterEvaluate { kspKotlin dependsOn generateGrammarSource }`.
tasks.matching { it.name == "kspKotlinJvm" }.configureEach {
  dependsOn(generateGrammarSource)
}
tasks.matching { it.name == "kspTestKotlinJvm" }.configureEach {
  enabled = false
}

tasks.named<Test>("jvmTest") {
  useJUnitPlatform()
}

val spaceUsername: String by project
val spacePassword: String by project
val engineVersion: String by project
val uploadUrl: String by project

publishing {
  publications {
    // KMP registers its own publications; configure rather than create.
    withType<MavenPublication>().configureEach {
      groupId = "com.maddyhome.idea.vim"
      version = engineVersion
    }
  }
  repositories {
    maven {
      if (uploadUrl.isNotEmpty()) {
        url = uri(uploadUrl)
        credentials {
          username = spaceUsername
          password = spacePassword
        }
      }
    }
  }
}

artifacts.add(sourcesJarArtifacts.name, tasks.named("jvmSourcesJar"))

// KMP renames the JVM test task from `test` to `jvmTest`. `./gradlew test` matches
// by task NAME across projects, so without this alias it silently skips all 530 of
// vim-engine's tests - 0 failures, 530 fewer tests, and a green build. Keeps the
// command documented in CLAUDE.md and used by CI honest.
tasks.register("test") {
  dependsOn("jvmTest")
}
