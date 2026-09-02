/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// The VS Code extension, written in Kotlin/JS.
//
// Written in Kotlin rather than TypeScript for one concrete reason: Kotlin/JS eliminates everything
// not reachable from an `@JsExport` root, so a TypeScript extension would need a hand-maintained
// export facade over the engine's 836 common files, and phase 4 showed how quietly that surface
// fails - the engine's own library compiled to a 561-byte shell exporting nothing, and no test
// could see it. Compiled together with the engine, the extension *is* the reachable root, so only
// VS Code's own entry points need exporting.

plugins {
  kotlin("multiplatform")
}

repositories {
  maven { url = uri("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2") }
}

kotlin {
  js(IR) {
    // The name of the bundle VS Code loads, and of nothing else. Without it the file is named from
    // the Gradle project - `IdeaVIM-vscode-extension.js` - which is the wrong product's name on the
    // one file a user of this extension could ever look at. The engine keeps its own name; it is a
    // dependency, and it really is IdeaVim's.
    outputModuleName.set("vimperor")

    // VS Code loads extensions with `require`, so the output has to be CommonJS rather than ESM.
    useCommonJs()
    nodejs {
      testTask {
        // The default is two seconds, and replaying six hundred of IdeaVim's fixtures takes longer
        // than that on its own. A timeout that trips on corpus size rather than on a hang is a
        // build that fails for the wrong reason every time the corpus grows.
        useMocha { timeout = "60s" }
      }
    }
    binaries.executable()
  }

  sourceSets {
    val jsMain by getting {
      dependencies {
        implementation(project(":vim-engine"))
      }
    }

    val jsTest by getting {
      dependencies {
        implementation(kotlin("test"))
        // `vscode` is injected by the extension host and has no published package, so the external
        // declarations resolve to nothing under Node and every test that touches them fails at
        // load. Pointing the name at a local stub lets the VS Code-facing code be tested as
        // ordinary Kotlin instead of through a JavaScript harness.
        implementation(npm("vscode", File(projectDir, "src/jsTest/vscode-stub")))
        // The real API, for `checkVsCodeApiDeclarations` to check the external declarations
        // against. A type definition rather than code - nothing imports it.
        implementation(npm("@types/vscode", "1.85.0"))
      }
    }
  }
}

// The bundle VS Code loads. `package.json` points `main` straight at it, so
// `code --extensionDevelopmentPath=vscode-extension` runs whatever the last build produced -
// no packaging step between a Gradle build and a reloaded extension host.
val bundleDirectory = layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")

/**
 * Runs the bundle the way VS Code would, in a stubbed extension host.
 *
 * The Kotlin/JS tests prove the engine works when compiled *with* a test, which says nothing about
 * the bundle a host loads - and phase 4 is why that distinction is not academic: the engine's
 * library passed every test while compiling to a 561-byte shell exporting nothing. Static checks on
 * the bundle are no better. Both of the obvious ones pass on a bundle with the engine cut out: the
 * `require` of the engine survives because the engine's own `@JsExport` is re-exported through the
 * extension, and 579KB of engine survives with it. Running it is the only check that separates an
 * engine that is live from one that is merely present, and it is what caught the entry points being
 * exported under their Kotlin package path, where VS Code never looks.
 *
 * Uses the Node that the Kotlin JS plugin already downloads, so this needs nothing on the PATH.
 */
val runInStubHost by tasks.registering(Exec::class) {
  description = "Loads the built extension in a stubbed VS Code host and checks the engine responds."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  val nodeSetup = rootProject.tasks.named<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask>("kotlinNodeJsSetup")
  dependsOn("jsProductionExecutableCompileSync", nodeSetup)

  val script = layout.projectDirectory.file("src/hostTest/activate-in-a-stub-host.js")
  val manifest = layout.projectDirectory.file("package.json")
  inputs.file(script)
  inputs.file(manifest)
  inputs.dir(bundleDirectory)
  outputs.upToDateWhen { false }

  val node = nodeSetup.map { setup ->
    val home = setup.destinationProvider.get().asFile
    val unix = File(home, "bin/node")
    (if (unix.isFile) unix else File(home, "node.exe")).absolutePath
  }
  executable = node.get()
  args(script.asFile.absolutePath)
}

tasks.named("check") {
  dependsOn(runInStubHost)
}

/**
 * That every `external` declaration exists in the real VS Code API.
 *
 * These declarations are the one part of this module the compiler cannot check: nothing is compiled
 * against `vscode`, because the extension host injects it at runtime. A wrong shape fails when a
 * user presses a key, and the stub the tests run against is written from the same reading of the
 * documentation that the declarations are - so it agrees with them whether or not they are right.
 *
 * `@types/vscode` is the actual API surface, published by the VS Code team. Checking against it
 * turned up `ThemeColor` being a constructor rather than a value, which nothing else would have
 * found until a real window ran it.
 *
 * Names, not signatures: a full TypeScript parse is a different project. It catches the member that
 * does not exist, which is the mistake that actually gets made.
 */
val checkVsCodeApiDeclarations by tasks.registering {
  description = "Checks every `external` VS Code declaration against @types/vscode."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
  val declarations = layout.projectDirectory.file("src/jsMain/kotlin/com/maddyhome/idea/vim/vscode/VsCodeApi.kt")
  val typings = rootProject.layout.buildDirectory.file("js/node_modules/@types/vscode/index.d.ts")
  inputs.file(declarations)
  outputs.upToDateWhen { false }

  doLast {
    val api = typings.get().asFile
    check(api.isFile) { "No @types/vscode at $api - the npm dependency did not install." }
    val realApi = api.readText()

    // Split on top-level `external` so a body-less declaration cannot swallow the next one's members.
    val ours = declarations.asFile.readText()
      .split(Regex("^external ", RegexOption.MULTILINE))
      .drop(1)
      .flatMap { part ->
        val owner = Regex("^(?:interface|object|class) (\\w+)").find(part)?.groupValues?.get(1)
          ?: return@flatMap emptyList()
        val body = part.substringBefore("\n}")
        Regex("^  (?:val|var|fun) (\\w+)", RegexOption.MULTILINE).findAll(body)
          .map { owner to it.groupValues[1] }
          .toList()
      }

    // Declared here rather than by VS Code: `subscriptions` is a plain array in the real API, so
    // `push` comes from JavaScript; `Thenable` is a global interface outside the `vscode` namespace.
    val notInTheNamespace = setOf("Subscriptions", "Thenable")

    // Kotlin names that stand for a differently named thing in the API, because the API's own name
    // is already taken here - `Uri` is one class in VS Code and an interface plus an object here.
    val aliases = mapOf("UriFactory" to "Uri")

    val missing = ours.filter { (owner, member) ->
      if (owner in notInTheNamespace) return@filter false
      val realName = aliases[owner] ?: owner
      val block = Regex("export (?:interface|class|namespace|enum) $realName\\b.*?\\n\\t?\\}", RegexOption.DOT_MATCHES_ALL)
        .find(realApi)?.value
      block != null && !Regex("\\b${Regex.escape(member)}\\b").containsMatchIn(block)
    }

    check(missing.isEmpty()) {
      "These are declared as VS Code API but are not in @types/vscode:\n" +
        missing.joinToString("\n") { (owner, member) -> "  $owner.$member" }
    }

    // And that a thing VS Code declares as a *class* is declared as a class here.
    //
    // This is the check that was missing when a real window refused the first keystroke with
    // `Illegal argument: selections`. `Selection` is a class in VS Code, was an interface here, and
    // its setter is not duck-typed: `TextEditor.selections` does `!(a instanceof Selection)` and
    // throws. A Kotlin class implementing an interface of the right shape is a plain object, so
    // every caret this host pushed was refused - and nothing offline saw it, because the stub host
    // takes what it is handed and the check above compares names.
    //
    // The two exceptions are classes this module only ever *receives*. Receiving an instance
    // through an interface is fine; it is constructing one that is not.
    val receivedOnly = setOf("Disposable", "Uri")
    val declaredAsInterface = Regex("^external interface (\\w+)", RegexOption.MULTILINE)
      .findAll(declarations.asFile.readText())
      .map { it.groupValues[1] }
      .filterNot { it in notInTheNamespace || it in receivedOnly }
      .filter { name -> Regex("export class $name\\b").containsMatchIn(realApi) }
      .toList()

    check(declaredAsInterface.isEmpty()) {
      "VS Code declares these as classes, so an object of ours will not pass its `instanceof` " +
        "checks - declare them as `external class` here:\n" +
        declaredAsInterface.joinToString("\n") { "  $it" }
    }

    logger.lifecycle("Checked ${ours.size} VS Code API declarations against the real API.")
  }
}

tasks.named("check") {
  dependsOn(checkVsCodeApiDeclarations)
}

/**
 * That every VS Code command id this module sends is in `VsCodeCommands`, where it can be checked.
 *
 * A command id is the least verifiable thing here. `@types/vscode` describes the API but publishes
 * no list of command ids, so nothing offline can say whether `workbench.action.focusBelowGroup` is
 * a command VS Code has - and a test asserting that string is asserting that the test and the code
 * agree, which they will however wrong the id is. The only list that exists is `getCommands`, and
 * the extension asks for it at activation.
 *
 * That check is worth exactly as much as `VsCodeCommands.all` is complete, and completeness is the
 * kind of thing a hand-maintained list quietly loses. So: no id lives anywhere else. This fails on
 * a `workbench.` or `editor.` literal outside that file, and on a constant in it that never made it
 * into `all`.
 */
val checkVsCodeCommandIds by tasks.registering {
  description = "Checks that every VS Code command id lives in VsCodeCommands, and is listed in `all`."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  val sources = layout.projectDirectory.dir("src/jsMain/kotlin")
  inputs.dir(sources)
  outputs.upToDateWhen { false }

  doLast {
    val registryName = "VsCodeIds.kt"
    val idLiteral = Regex("\"(workbench|editor|vscode)\\.[A-Za-z0-9.$'{}+ ]*\"")

    val stray = sources.asFile.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name != registryName }
      .flatMap { file ->
        idLiteral.findAll(file.readText()).map { "${file.name}: ${it.value}" }
      }
      .toList()

    check(stray.isEmpty()) {
      "VS Code ids belong in $registryName, where the activation check can see them:\n" +
        stray.joinToString("\n") { "  $it" }
    }

    val registry = File(sources.asFile, "com/maddyhome/idea/vim/vscode/$registryName")
    check(registry.isFile) { "No $registryName - the command registry has moved or gone." }
    val text = registry.readText()
    val commandsObject = text.substringAfter("internal object VsCodeCommands {").substringBefore("\n}")
    val declared = Regex("const val (\\w+)").findAll(commandsObject).map { it.groupValues[1] }.toList()
    val listed = commandsObject.substringAfter("val all: List<String> = listOf(").substringBefore(")")
    val unlisted = declared.filterNot { Regex("\\b$it\\b").containsMatchIn(listed) }

    check(unlisted.isEmpty()) {
      "These commands are declared but not in `VsCodeCommands.all`, so nothing checks them against " +
        "the real VS Code:\n" + unlisted.joinToString("\n") { "  $it" }
    }
    logger.lifecycle("Checked ${declared.size} VS Code command ids are registered for the runtime check.")
  }
}

tasks.named("check") {
  dependsOn(checkVsCodeCommandIds)
}

/**
 * Puts the `vscode` stub where Node will find it, every time, rather than when yarn feels like it.
 *
 * The stub is declared as a local npm dependency so the module resolves, and yarn *copies* a
 * `file:` dependency at install time - then considers itself up to date, so an edit to the stub is
 * invisible until `node_modules` is deleted by hand. The failure that produces is a `TypeError` on
 * a property that plainly exists in the source, which is a bad hour for whoever meets it.
 */
val syncVsCodeStub by tasks.registering(Sync::class) {
  from(layout.projectDirectory.dir("src/jsTest/vscode-stub"))
  into(rootProject.layout.buildDirectory.dir("js/node_modules/vscode"))
  mustRunAfter(rootProject.tasks.named("kotlinNpmInstall"))
}

tasks.named("jsNodeTest") {
  dependsOn(syncVsCodeStub)

  // `VimFixtureReplayTest` reads IdeaVim's test sources and its own baseline off the disk rather
  // than through the compiler, so Gradle cannot see them. Without this the harness is skipped as
  // up to date when the only thing that changed is a fixture or the baseline - which it did, and
  // the check that the baseline gate works passed by not running.
  inputs.dir(rootProject.layout.projectDirectory.dir("src/test"))
  inputs.dir(layout.projectDirectory.dir("src/jsTest/fixtures"))

  // ...and `KeybindingManifestTest` reads the manifest the same way, for the same reason: what VS
  // Code will hand this extension is decided in `package.json`, not in any Kotlin. Without this,
  // editing the manifest leaves the one test that checks it sitting there up to date.
  inputs.file(layout.projectDirectory.file("package.json"))
}

// `./gradlew test` matches by task NAME across projects, and a KMP module has no `test` task - so
// without this alias the gate documented in CLAUDE.md walks straight past this module, greenly.
// The same omission hid vim-engine's JS tests for a while; see the note on its `test` task.
tasks.register("test") {
  dependsOn("jsNodeTest")
  dependsOn(runInStubHost)
  dependsOn(checkVsCodeApiDeclarations)
  dependsOn(checkVsCodeCommandIds)
}
