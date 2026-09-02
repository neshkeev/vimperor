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

import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask
import java.util.Properties

plugins {
    kotlin("multiplatform")
//    id("org.jlleitschuh.gradle.ktlint")
    id("com.strumenta.antlr-kotlin") version "1.0.13"
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

// --- ANTLR, generated as Kotlin for every target
//
// This was the ANTLR Java tool driven through JavaExec, feeding generated Java into the jvm
// target's `compileJvmMainJava`. That works, and only works there: a JS target has no Java. The
// grammars are now compiled by antlr-kotlin into `commonMain`, so both targets run the *same*
// generated parser rather than two parsers that have to be kept saying the same thing.
//
// The grammars carry three edits for this, all of them in phase 0's report: `RegexLexer.g4`'s
// members block is Kotlin instead of Java, its actions call `markIgnoreCase` rather than
// `setIgnoreCase` (which would collide with the generated setter for `var ignoreCase`), and
// `RegexParser.g4`'s `start=` label is `rangeStart=`, because `start` is a final member of the
// Kotlin runtime's ParserRuleContext. Java hid that collision silently.
val generateKotlinGrammarSource by tasks.registering(AntlrKotlinTask::class) {
  source = fileTree(layout.projectDirectory.dir("antlr")) { include("**/*.g4") }
  packageName = "com.maddyhome.idea.vim.parser.generated"
  arguments = listOf("-visitor")
  outputDirectory =
    layout.buildDirectory.dir("generatedAntlr/com/maddyhome/idea/vim/parser/generated").get().asFile
}

ksp {
  arg("generated_directory", "$projectDir/src/jvmMain/resources/ksp-generated")
  arg("vimscript_functions_file", "engine_vimscript_functions.json")
  arg("ex_commands_file", "engine_ex_commands.json")
  arg("commands_file", "engine_commands.json")
  arg("extensions_file", "ideavim_extensions.json")
}

// The engine's message bundle is a JVM `.properties` resource, which a JS target cannot read. This
// emits the same key/value pairs as a Kotlin map for jsMain, so the two hosts serve identical text.
// Generated rather than checked in: these strings are user-visible, and a copy would drift silently.
val engineBundle = layout.projectDirectory.file("src/jvmMain/resources/messages/IdeaVimEngineBundle.properties")
val generatedBundleDir = layout.buildDirectory.dir("generated/messages/kotlin")

val generateJsMessageBundle by tasks.registering {
  // Captured as locals so the action closes over plain values, not the build script: the
  // configuration cache cannot serialize script object references.
  val bundleFile = engineBundle.asFile
  val outputDir = generatedBundleDir
  inputs.file(bundleFile)
  outputs.dir(outputDir)
  doLast {
    val properties = Properties()
    bundleFile.inputStream().use { properties.load(it) }
    val out = outputDir.get().file("com/maddyhome/idea/vim/helper/GeneratedMessages.kt").asFile
    out.parentFile.mkdirs()
    val entries = properties.stringPropertyNames().sorted().joinToString("\n") { key ->
      "  \"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\" to \"" +
        properties.getProperty(key).replace("\\", "\\\\").replace("\"", "\\\"")
          .replace("\n", "\\n").replace("\r", "\\r").replace("$", "\${'$'}") + "\","
    }
    out.writeText(
      buildString {
        appendLine("// Generated from IdeaVimEngineBundle.properties by generateJsMessageBundle. Do not edit.")
        appendLine("package com.maddyhome.idea.vim.helper")
        appendLine()
        appendLine("internal val GENERATED_ENGINE_MESSAGES: Map<String, String> = mapOf(")
        appendLine(entries)
        appendLine(")")
      }
    )
  }
}

// The JVM reads its command list from a JSON resource and turns each class name into a constructor
// by way of the class loader. A JS host has neither, so the same JSON is turned into Kotlin here:
// direct constructor calls, resolved at compile time. This is the "generated registry" the KDoc on
// `reflectiveFactory` describes - the seam that made the providers platform-neutral.
//
// The upshot is that a missing or misspelled class name is a JS compile error, where on the JVM it
// is a crash the first time that command is pressed.
val kspGeneratedDir = layout.projectDirectory.dir("src/jvmMain/resources/ksp-generated")
val generatedRegistryDir = layout.buildDirectory.dir("generated/registry/kotlin")

val generateJsCommandRegistry by tasks.registering {
  // Locals, not script references: the configuration cache cannot serialize the latter.
  val commandsJson = kspGeneratedDir.file("engine_commands.json").asFile
  val functionsJson = kspGeneratedDir.file("engine_vimscript_functions.json").asFile
  val exCommandsJson = kspGeneratedDir.file("engine_ex_commands.json").asFile
  val outputDir = generatedRegistryDir
  inputs.file(commandsJson)
  inputs.file(functionsJson)
  inputs.file(exCommandsJson)
  outputs.dir(outputDir)
  doLast {
    fun quote(value: String): String =
      "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}") + "\""

    // Kotlin's hard keywords are legal Java package segments, and the engine has a package called
    // `object` (the text objects). Emitting fully qualified constructor calls rather than imports
    // also settles the other name problem: two different packages both declare an
    // `InsertRegisterAction`, so simple names are not unique across this list.
    val hardKeywords = setOf(
      "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
      "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
      "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    fun escapeQualifiedName(className: String): String =
      className.split(".").joinToString(".") { if (it in hardKeywords) "`" + it + "`" else it }

    val slurper = groovy.json.JsonSlurper()

    @Suppress("UNCHECKED_CAST")
    val beans = slurper.parse(commandsJson) as List<Map<String, String>>

    @Suppress("UNCHECKED_CAST")
    val functions = slurper.parse(functionsJson) as Map<String, String>

    @Suppress("UNCHECKED_CAST")
    val exCommands = slurper.parse(exCommandsJson) as Map<String, Map<String, Any>>

    // Grouped by class, exactly as JsonCommandProvider groups them: one LazyVimCommand per handler,
    // carrying every key sequence bound to it.
    val grouped = beans.groupBy { it.getValue("class") }
    val commandEntries = grouped.entries.sortedBy { it.key }.joinToString("\n") { (className, rows) ->
      val keys = rows.map { quote(it.getValue("keys")) }.joinToString(", ")
      val modes = quote(rows.first().getValue("modes"))
      "  entry(listOf($keys), $modes, ${quote(className)}) { ${escapeQualifiedName(className)}() },"
    }
    val functionEntries = functions.entries.sortedBy { it.key }.joinToString("\n") { (name, className) ->
      "  LazyVimscriptFunction(${quote(name)}) { ${escapeQualifiedName(className)}() },"
    }

    // `standardConstructor` is the annotation processor's answer, computed from the declared types.
    // The 18 classes without one are the ones CommandVisitor builds itself, and they get a null
    // factory here exactly as `lazyExCommand` gives them on the JVM.
    // Every ex-command class is reachable from commonMain now, so nothing is excluded here. If one
    // ever is not, this is where the exclusion goes - and `GeneratedEngineRegistryTest` asserts the
    // registry is complete, so the gap would show up as a failing test rather than a silent hole.
    val exCommandEntries = exCommands.entries
      .sortedBy { it.key }.joinToString("\n") { (name, bean) ->
      val className = escapeQualifiedName(bean.getValue("class") as String)
      val factory =
        if (bean.getValue("standardConstructor") as Boolean) {
          "{ range, modifier, argument -> $className(range, modifier, argument) }"
        } else {
          "null"
        }
      "  ${quote(name)} to LazyExCommandInstance($className::class, $factory),"
    }

    val out = outputDir.get().file("com/maddyhome/idea/vim/GeneratedEngineRegistry.kt").asFile
    out.parentFile.mkdirs()
    out.writeText(
      buildString {
        appendLine("// Generated from ksp-generated/*.json by generateJsCommandRegistry. Do not edit.")
        appendLine("@file:Suppress(\"ktlint\", \"RedundantVisibilityModifier\")")
        appendLine()
        appendLine("package com.maddyhome.idea.vim")
        appendLine()
        appendLine("import com.maddyhome.idea.vim.action.CommandProvider")
        appendLine("import com.maddyhome.idea.vim.action.change.LazyVimCommand")
        appendLine("import com.maddyhome.idea.vim.api.injector")
        appendLine("import com.maddyhome.idea.vim.command.MappingMode")
        appendLine("import com.maddyhome.idea.vim.handler.EditorActionHandlerBase")
        appendLine("import com.maddyhome.idea.vim.vimscript.model.functions.LazyVimscriptFunction")
        appendLine("import com.maddyhome.idea.vim.vimscript.model.commands.ExCommandProvider")
        appendLine("import com.maddyhome.idea.vim.vimscript.model.commands.LazyExCommandInstance")
        appendLine("import com.maddyhome.idea.vim.vimscript.model.functions.VimscriptFunctionProvider")
        appendLine()
        appendLine("/**")
        appendLine(" * One command as the annotation processor recorded it, with its handler's constructor already")
        appendLine(" * resolved. The key sequences stay unparsed: parsing them needs `injector`, which is not there")
        appendLine(" * yet when this list is built.")
        appendLine(" */")
        appendLine("internal class GeneratedCommandEntry(")
        appendLine("  val keys: List<String>,")
        appendLine("  val modes: String,")
        appendLine("  val className: String,")
        appendLine("  val factory: () -> EditorActionHandlerBase,")
        appendLine(")")
        appendLine()
        appendLine("private fun entry(")
        appendLine("  keys: List<String>,")
        appendLine("  modes: String,")
        appendLine("  className: String,")
        appendLine("  factory: () -> EditorActionHandlerBase,")
        appendLine(") = GeneratedCommandEntry(keys, modes, className, factory)")
        appendLine()
        appendLine("internal val GENERATED_ENGINE_COMMANDS: List<GeneratedCommandEntry> = listOf(")
        appendLine(commandEntries)
        appendLine(")")
        appendLine()
        appendLine("internal val GENERATED_ENGINE_FUNCTIONS: List<LazyVimscriptFunction> = listOf(")
        appendLine(functionEntries)
        appendLine(")")
        appendLine()
        appendLine("/** The JS twin of the JVM object of the same name, built without a class loader. */")
        appendLine("object EngineCommandProvider : CommandProvider {")
        appendLine("  override fun getCommands(): Collection<LazyVimCommand> =")
        appendLine("    GENERATED_ENGINE_COMMANDS.map {")
        appendLine("      LazyVimCommand(")
        appendLine("        it.keys.map { keys -> injector.parser.parseKeys(keys) }.toSet(),")
        appendLine("        it.modes.map { mode -> MappingMode.parseModeChar(mode) }.toSet(),")
        appendLine("        it.className,")
        appendLine("        it.factory,")
        appendLine("      )")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** The JS twin of the JVM object of the same name, built without a class loader. */")
        appendLine("object EngineFunctionProvider : VimscriptFunctionProvider {")
        appendLine("  override fun getFunctions(): Collection<LazyVimscriptFunction> = GENERATED_ENGINE_FUNCTIONS")
        appendLine("}")
        appendLine()
        appendLine("internal val GENERATED_ENGINE_EX_COMMANDS: Map<String, LazyExCommandInstance> = mapOf(")
        appendLine(exCommandEntries)
        appendLine(")")
        appendLine()
        appendLine("/** The JS twin of the JVM object of the same name, built without a class loader. */")
        appendLine("object EngineExCommandProvider : ExCommandProvider {")
        appendLine("  override fun getCommands(): Map<String, LazyExCommandInstance> = GENERATED_ENGINE_EX_COMMANDS")
        appendLine("}")
      }
    )
  }
}

// The Vimscript corpus differential, from phase 0's caveat 7.
//
// `vimscript-golden.txt` records what the **Java** ANTLR parser produced for 1,865 commands
// extracted from IdeaVim's own test suite. That parser no longer exists in this repository, so this
// file is the only surviving record of its behaviour and the only check that switching to
// antlr-kotlin did not change how Vimscript parses.
//
// Emitted as Kotlin rather than read as a resource so it runs on **both** targets. Caveat 7 also
// noted that the tab-delimited format would break on an input containing a literal tab - verified
// zero today, but latent. Generating separate string literals removes the format entirely, and the
// check below fails the build rather than silently mis-splitting if one ever appears.
val vimscriptGolden = layout.projectDirectory.file("corpus/vimscript-golden.txt")
val generatedCorpusDir = layout.buildDirectory.dir("generated/corpus/kotlin")

val generateVimscriptCorpus by tasks.registering {
  val goldenFile = vimscriptGolden.asFile
  val outputDir = generatedCorpusDir
  inputs.file(goldenFile)
  outputs.dir(outputDir)
  doLast {
    fun quote(value: String): String =
      "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\${'$'}") + "\""

    val entries = goldenFile.readLines().filter { it.isNotEmpty() }.map { line ->
      val parts = line.split("\t")
      require(parts.size == 2) {
        "corpus line does not split into exactly an input and a tree, so one of them contains a " +
          "tab and the golden file needs a different format: $line"
      }
      "  " + quote(parts[0]) + " to " + quote(parts[1]) + ","
    }

    val out = outputDir.get().file("com/maddyhome/idea/vim/vimscript/GeneratedVimscriptCorpus.kt").asFile
    out.parentFile.mkdirs()
    out.writeText(
      buildString {
        appendLine("// Generated from corpus/vimscript-golden.txt by generateVimscriptCorpus. Do not edit.")
        appendLine("package com.maddyhome.idea.vim.vimscript")
        appendLine()
        appendLine("/** Command text to the parse tree the Java ANTLR parser produced for it. */")
        appendLine("internal val VIMSCRIPT_CORPUS: List<Pair<String, String>> = listOf(")
        entries.forEach { appendLine(it) }
        appendLine(")")
      }
    )
  }
}


// `:smile` prints ASCII art chosen by the file's extension, read until now from four classpath
// resources. Same problem as the message bundle: a JS host has no classpath. The .txt files stay
// the source of truth and are emitted as a Kotlin map, so both targets print identical art.
val asciiArtDir = layout.projectDirectory.dir("src/jvmMain/resources/ascii-art")
val generatedAsciiArtDir = layout.buildDirectory.dir("generated/ascii-art/kotlin")

val generateAsciiArt by tasks.registering {
  val artDir = asciiArtDir.asFile
  val outputDir = generatedAsciiArtDir
  inputs.dir(artDir)
  outputs.dir(outputDir)
  doLast {
    fun quote(value: String): String =
      "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\${'$'}").replace("\n", "\\n").replace("\r", "\\r") + "\""

    val entries = artDir.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sortedBy { it.name }
      .joinToString("\n") { file ->
        "  " + quote("/ascii-art/" + file.name) + " to " + quote(file.readText()) + ","
      }
    val out = outputDir.get().file("com/maddyhome/idea/vim/vimscript/model/commands/GeneratedAsciiArt.kt").asFile
    out.parentFile.mkdirs()
    out.writeText(
      buildString {
        appendLine("// Generated from src/jvmMain/resources/ascii-art by generateAsciiArt. Do not edit.")
        appendLine("package com.maddyhome.idea.vim.vimscript.model.commands")
        appendLine()
        appendLine("internal val GENERATED_ASCII_ART: Map<String, String> = mapOf(")
        appendLine(entries)
        appendLine(")")
      }
    )
  }
}


// A JS library with no `@JsExport` compiles to a shell that exports nothing: Kotlin/JS eliminates
// everything not reachable from an exported root, and only `@JsExport` creates one. That failure is
// silent - the build succeeds, and the Kotlin/JS tests keep passing, because test code is itself a
// root compiled alongside the engine. It went unnoticed until something tried to `require` the
// library from JavaScript and got `{}`.
//
// This asserts the artifact still carries the engine. The threshold is deliberately far below the
// real size (~580 KB with a single export) and far above an empty shell (561 bytes): it is here to
// catch the difference between "everything" and "nothing", not to police growth.
val checkJsLibraryIsNotEmpty by tasks.registering {
  val library = layout.buildDirectory.file("dist/js/productionLibrary/IdeaVIM-vim-engine.js")
  dependsOn("jsNodeProductionLibraryDistribution")
  inputs.file(library)
  outputs.upToDateWhen { false }
  doLast {
    val file = library.get().asFile
    val size = file.length()
    check(size > 100_000) {
      "The JS library is $size bytes, which means dead-code elimination stripped the engine: " +
        "nothing is reachable from an `@JsExport` declaration. See EngineExports.kt. " +
        "A JavaScript consumer would import an empty object."
    }
  }
}


kotlin {
  jvm()
  js(IR) {
    nodejs {
      testTask {
        useMocha {
          // The corpus differential parses 1,865 commands in one test, which is past Mocha's 2s
          // default. Node is slower than the JVM here by enough to matter - see the finding.
          timeout = "60s"
        }
      }
    }
    binaries.library()
  }

  compilerOptions {
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
  }

  sourceSets {
    val commonMain by getting {
      // Phase 1 task 5. Only files with no JVM-API dependency live here; the
      // move-list is docs/superpowers/plans/2026-08-16-phase-1-task-4-move-list.tsv.
      // The task provider, not the directory - see the note on jsMain below for why.
      kotlin.srcDir(generateKotlinGrammarSource)
      kotlin.srcDir(generateAsciiArt)
      dependencies {
        implementation("com.strumenta:antlr-kotlin-runtime:1.0.13")
        // :api is multiplatform as of W6, so common code can depend on it. `implementation`
        // rather than `api`, matching the visibility this had when it sat in jvmMain.
        implementation(project(":api"))
        implementation(project(":vim-annotations"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        // Multiplatform, unlike the -jvm artifact jvmMain uses. compileOnly to match the rest of
        // the project: the IDE provides it at runtime and it must not be bundled.
        compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinxSerializationVersion")
      }
    }
    val jvmMain by getting {
      // src/jvmMain/{kotlin,resources} are KMP defaults - no srcDir needed.
      dependencies {
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly(project(":annotation-processors"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$kotlinxSerializationVersion")
        compileOnly(kotlin("reflect"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
      }
    }
    val commonTest by getting {
      kotlin.srcDir(generateVimscriptCorpus)
      // Tests of platform-neutral behaviour, run on *every* target. The differential tests that
      // compare against java.lang.* stay in jvmTest - they need the JDK to compare against - so
      // this is where the shared contracts get checked on JS as well as the JVM.
      dependencies {
        implementation(kotlin("test"))
      }
    }
    val jsMain by getting {
      // The task provider, not the directory: that is what makes Gradle run the generator before
      // compiling. Wiring the bare directory compiles fine until someone runs `clean`, which is
      // exactly how this was found.
      kotlin.srcDir(generateJsMessageBundle)
      kotlin.srcDir(generateJsCommandRegistry)
    }
    val jsTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation("org.junit.jupiter:junit-jupiter-api:6.0.0")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.0")
        runtimeOnly("org.junit.vintage:junit-vintage-engine:6.1.2")
        implementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
        implementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
        // The engine has kotlinx.serialization as compileOnly - the IDE supplies it at runtime -
        // so the JSON providers cannot read their resources in this module's tests without it.
        // Only the JVM needs this: JS reads a generated registry, not JSON.
        runtimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
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
  add("kspJvm", project(":annotation-processors"))
}

// KSP's tasks are registered lazily by the plugin, so match rather than name().
// KSP reads the engine's own annotated sources, which reference the generated parser, so the
// grammars still have to be built first.
tasks.matching { it.name == "kspKotlinJvm" }.configureEach {
  dependsOn(generateKotlinGrammarSource)
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
// by task NAME across projects, so without this alias it silently skips all of
// vim-engine's tests - 0 failures, hundreds fewer tests, and a green build. Keeps the
// command documented in CLAUDE.md and used by CI honest.
//
// jsNodeTest is here for the same reason and was caught the same way: commonTest compiles for both
// targets, so the JS half ran and passed locally while `./gradlew test` reported zero JS tests. A
// target whose tests are not in the gate is a target whose tests rot.
tasks.register("test") {
  dependsOn("jvmTest")
  dependsOn("jsNodeTest")
  // Neither test task can see this: they compile *with* the engine, so the engine is always
  // reachable for them and always stripped from the library. Only building the library finds it.
  dependsOn(checkJsLibraryIsNotEmpty)
}
