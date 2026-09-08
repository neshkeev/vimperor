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
    // the Gradle project - `vimperor-vscode-extension.js` - and `package.json` names this file as
    // `main`. The other modules need no such setting: they are `<rootProject.name>-<module>.js`,
    // and the root is `vimperor`. This one is the exception because its name is not that pattern.
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
    // Maven layout. This module has only a JS target, so src/main/kotlin and src/test/kotlin
    // carry no ambiguity about which target they are for. Note that this module's src/test is
    // not the repository root's - that one is IdeaVim's test corpus, read as data.
    val jsMain = getByName("jsMain") {
      kotlin.setSrcDirs(listOf("src/main/kotlin"))
      dependencies {
        implementation(project(":vim-engine"))
        // The thin API an extension is written against. `vim-engine` depends on it too, but as
        // `implementation`, so it is not on this module's compile classpath without saying so -
        // and this module names the type an extension's `init` is handed.
        implementation(project(":api"))
      }
    }

    val jsTest = getByName("jsTest") {
      kotlin.setSrcDirs(listOf("src/test/kotlin"))
      dependencies {
        implementation(kotlin("test"))
        // `vscode` is injected by the extension host and has no published package, so the external
        // declarations resolve to nothing under Node and every test that touches them fails at
        // load. Pointing the name at a local stub lets the VS Code-facing code be tested as
        // ordinary Kotlin instead of through a JavaScript harness.
        implementation(npm("vscode", File(projectDir, "src/test/vscode-stub")))
        // The real API, for `checkVsCodeApiDeclarations` to check the external declarations
        // against. A type definition rather than code - nothing imports it.
        implementation(npm("@types/vscode", "1.85.0"))
      }
    }
  }
}

// Where the Kotlin/JS compiler puts the production bundle. `dist/` below is a copy of it, and
// `package.json` points `main` there - see `assembleExtension` for why the copy exists.
val bundleDirectory = layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")

/**
 * The directory the published extension is loaded from, and the reason it exists.
 *
 * `main` used to point straight into `build/`, which is exactly right for
 * `code --extensionDevelopmentPath` and exactly wrong for a `.vsix`: `vsce` packages files relative
 * to the extension root, so shipping from `build/` means shipping the whole Kotlin/JS build - test
 * output, the incremental caches, the lot. `dist/` is the same seven files and nothing else.
 *
 * Seven, not one. Kotlin/JS emits a module per Gradle project plus the standard library and the
 * ANTLR runtime, and `vimperor.js` `require`s them by name from its own directory - so the whole
 * directory travels or none of it works.
 */
val distDirectory = layout.projectDirectory.dir("dist")

/**
 * Copies the production bundle into [distDirectory], without the source maps.
 *
 * The maps are 2.8MB against 6.6MB of code and they buy one thing: a stack trace in a bug report
 * that names Kotlin source lines. Kotlin/JS does not minify, so the names survive without them -
 * a trace still says `VimRegex` and `findAll` - and the cost is on every user's download rather
 * than on the rare report. They stay in `build/`, where a developer reproducing the report has
 * them anyway.
 */
val assembleExtension = tasks.register<Sync>("assembleExtension") {
  description = "Copies the production JavaScript into dist/, which is what the .vsix ships."
  group = LifecycleBasePlugin.BUILD_GROUP

  dependsOn("jsProductionExecutableCompileSync")
  from(bundleDirectory) {
    exclude("**/*.map")
  }
  into(distDirectory)
}

/**
 * The Node that the Kotlin plugin already downloaded, and the `npx` beside it.
 *
 * Nothing here needs Node on the `PATH`, which is the same promise `runInStubHost` makes: a build
 * that works on a machine with no system Node, and a build that cannot accidentally package with a
 * different one than it tested with.
 *
 * ## Decided by platform, and it has to be
 *
 * Windows keeps `node.exe` at the root of the distribution and everything else keeps `bin/node`.
 * This used to tell them apart by *asking the filesystem* - `if (File(home, "bin/node").isFile)` -
 * and that is a question with no answer at the moment it was asked. All of this runs while the task
 * graph is built, before `kotlinNodeJsSetup` has downloaded anything, so on a machine that has
 * never built this project neither candidate exists and the check falls through to the Windows
 * name.
 *
 * It passed everywhere it was ever run, because a developer's machine has downloaded Node once and
 * every later build finds it. A cold CI runner has not, and the first release build said
 *
 *     A problem occurred starting process 'command
 *     '/home/runner/.gradle/nodejs/node-v24.10.0-linux-x64/node.exe''
 *
 * on Linux. The platform is knowable without the filesystem; the layout is a fact about the
 * platform, not about what happens to be on disk yet.
 */
val onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

val nodeBinDirectory = rootProject.tasks
  .named<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask>("kotlinNodeJsSetup")
  .map { setup ->
    val home = setup.destinationProvider.get().asFile
    if (onWindows) home else File(home, "bin")
  }

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
val runInStubHost = tasks.register<Exec>("runInStubHost") {
  description = "Loads the built extension in a stubbed VS Code host and checks the engine responds."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  val nodeSetup = rootProject.tasks.named<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask>("kotlinNodeJsSetup")
  // Through `assembleExtension` rather than the compile, because this loads the bundle the way VS
  // Code does - `require` of whatever `main` names - and `main` names `dist/`. Which makes this
  // check better than it was: it now runs the same files the `.vsix` ships, so a file left out of
  // `dist/` fails here instead of in somebody's editor.
  dependsOn(assembleExtension, nodeSetup)

  val script = layout.projectDirectory.file("src/test/host/activate-in-a-stub-host.js")
  val manifest = layout.projectDirectory.file("package.json")
  inputs.file(script)
  inputs.file(manifest)
  inputs.dir(distDirectory)
  outputs.upToDateWhen { false }

  executable = File(nodeBinDirectory.get(), if (onWindows) "node.exe" else "node").absolutePath
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
val checkVsCodeApiDeclarations = tasks.register("checkVsCodeApiDeclarations") {
  description = "Checks every `external` VS Code declaration against @types/vscode."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
  val declarations = layout.projectDirectory.file("src/main/kotlin/com/github/neshkeev/vimperor/vscode/VsCodeApi.kt")
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
val checkVsCodeCommandIds = tasks.register("checkVsCodeCommandIds") {
  description = "Checks that every VS Code command id lives in VsCodeCommands, and is listed in `all`."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  val sources = layout.projectDirectory.dir("src/main/kotlin")
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

    val registry = File(sources.asFile, "com/github/neshkeev/vimperor/vscode/$registryName")
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
val syncVsCodeStub = tasks.register<Sync>("syncVsCodeStub") {
  from(layout.projectDirectory.dir("src/test/vscode-stub"))
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
  inputs.dir(layout.projectDirectory.dir("src/test/fixtures"))

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

// ---------------------------------------------------------------------------------------------
// Packaging
// ---------------------------------------------------------------------------------------------

/**
 * The extension's version, which is `package.json`'s and not Gradle's.
 *
 * Gradle's `version` here is `SNAPSHOT` - it belongs to the IntelliJ plugin, which this module only
 * shares a build with. `vsce` reads `package.json` too, so taking the name of the `.vsix` from the
 * same place is what keeps the file on disk and the version inside it from drifting apart.
 */
val extensionVersion: String by lazy {
  val manifest = layout.projectDirectory.file("package.json").asFile.readText()
  Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
    ?: error("no version in vscode-extension/package.json")
}

/**
 * The `vsce` this fork publishes with.
 *
 * Pinned, because an unpinned `npx` fetches whatever is newest at the moment somebody runs a
 * release - which is not a thing a release should depend on. `-PvsceVersion=` overrides it, which
 * is how a prerelease gets tried without a commit; see [vsceAuth] for the reason to want one.
 */
val vsceVersion: String = (findProperty("vsceVersion") as String?) ?: "3.9.2"

/**
 * `vsce`, run through `npx` so that nothing has to be installed into this repository.
 *
 * `dependsOn(assembleExtension)` is what builds `dist/` before `vsce` reads it, and it is the only
 * thing that does. `package.json` used to carry a `vscode:prepublish` script - `cd .. && ./gradlew
 * :vscode-extension:assembleExtension` - which `vsce` runs on its own account before packaging or
 * publishing, so a Gradle build was starting a second Gradle build of the same project from inside
 * an `Exec` task of the first. It showed up in the release log as two `BUILD SUCCESSFUL` lines in
 * one step, the inner one nine seconds of the outer one's twenty-four, every task already
 * `UP-TO-DATE` because the outer build had just done them.
 *
 * Redundant is the smaller half. Gradle inside Gradle shares a `GRADLE_USER_HOME`, a configuration
 * cache and a set of file locks with the build that spawned it, which is a thing to have go wrong
 * during a release rather than before one.
 */
fun Exec.vsce(vararg arguments: String) {
  dependsOn(assembleExtension)
  workingDir = layout.projectDirectory.asFile
  val bin = nodeBinDirectory.get()
  executable = File(bin, if (onWindows) "npx.cmd" else "npx").absolutePath
  args(listOf("--yes", "@vscode/vsce@$vsceVersion") + arguments)
  environment("PATH", bin.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
}

/**
 * How `vsce` proves who we are - the part of publishing that has a deadline on it.
 *
 * Azure DevOps retires global personal access tokens on **1 December 2026**, and a PAT is what
 * this fork published with. Microsoft's replacement is a Microsoft Entra ID token, which `vsce`
 * takes with `--azure-credential`: it asks `@azure/identity` for an Azure DevOps token for
 * whatever identity the machine is already signed in as, so the credential is the sign-in rather
 * than a long-lived secret somebody has to store, rotate and eventually leak.
 *
 * Three modes, and the default is the one that needs no stored secret:
 *
 *  - `entra` - `--azure-credential`. Locally that is `az login`; in CI it is a federated sign-in.
 *  - `pat` - `VSCE_PAT`. Works until December 2026 and then stops, everywhere, at once.
 *  - `oidc` - `--oidc`. `vsce` asks GitHub Actions for its own OIDC token and trades it with the
 *    Marketplace for a credential, so CI needs no Azure tenant and no secret at all. This is the
 *    right answer for [the release workflow][1] and the reason `vsceVersion` is overridable: as of
 *    September 2026 the flag is hidden from `--help` and ships only in `@vscode/vsce@next`.
 *
 * A `VSCE_PAT` in the environment picks `pat`, because a PAT that is set is a PAT that was meant
 * to be used. `-PvsceAuth=` overrides that either way.
 *
 * [1]: ../.github/workflows/publish-vimperor.yml
 */
val vsceAuth: String = (findProperty("vsceAuth") as String?)
  ?: if (System.getenv("VSCE_PAT").isNullOrBlank()) "entra" else "pat"

/** Adds whatever [vsceAuth] asks for to a `vsce` command that talks to the Marketplace. */
fun Exec.authenticate() {
  when (vsceAuth) {
    "pat" -> doFirst {
      check(!System.getenv("VSCE_PAT").isNullOrBlank()) {
        "VSCE_PAT is not set. See vscode-extension/PUBLISHING.md - and note that personal access " +
          "tokens stop working in December 2026; -PvsceAuth=entra is the replacement."
      }
    }

    // Emptied rather than left alone: `getPAT` in vsce reads `--pat` before `--azure-credential`,
    // and `--pat` defaults to `$VSCE_PAT`. A PAT still in the environment would quietly win, and
    // "it published, so the Entra setup must work" is the wrong thing to come away believing.
    "entra" -> { args("--azure-credential"); environment("VSCE_PAT", "") }
    "oidc" -> { args("--oidc"); environment("VSCE_PAT", "") }

    else -> error("unknown -PvsceAuth=$vsceAuth: one of entra, pat, oidc")
  }
}

/**
 * Builds the `.vsix`, which is the file the Marketplace takes and the file you can install by hand.
 *
 * `code --install-extension vimperor-<version>.vsix` is the whole of testing a release, and it is
 * worth doing before publishing one: it is the only check that runs the extension the way a user
 * gets it rather than the way a developer does.
 */
val packageExtension = tasks.register<Exec>("packageExtension") {
  description = "Builds the .vsix that the Marketplace takes."
  group = "distribution"
  vsce("package", "--out", "build/vimperor-$extensionVersion.vsix")
}

/**
 * That the packaged `.vsix` is self-sufficient - the one check `.vscodeignore` can fail.
 *
 * `runInStubHost` loads the extension out of the working tree, where every file exists whether or
 * not it is packaged. This unpacks the archive somewhere else and runs the same script inside it,
 * so a file left out of the package fails here rather than in the first user's editor. That is a
 * real risk and not a theoretical one: `.vscodeignore` is a list of exclusions, and the way it goes
 * wrong is by excluding one file too many.
 *
 * The script is copied into the unpacked tree because it locates the extension from its own path -
 * which is the right thing for it to do, and means it tests whatever tree it is standing in.
 */
val unpackExtension = tasks.register<Sync>("unpackExtension") {
  description = "Unpacks the .vsix, so that what was packaged can be run rather than trusted."
  dependsOn(packageExtension)

  from(zipTree(layout.buildDirectory.file("vimperor-$extensionVersion.vsix")))
  into(layout.buildDirectory.dir("packaged-extension"))

  // The script locates the extension from its own path, which is the right thing for it to do and
  // means it tests whatever tree it is standing in. So it is put into this one.
  val script = layout.projectDirectory.file("src/test/host/activate-in-a-stub-host.js")
  val destination = layout.buildDirectory.file("packaged-extension/extension/src/test/host/activate-in-a-stub-host.js")
  doLast {
    val into = destination.get().asFile
    into.parentFile.mkdirs()
    script.asFile.copyTo(into, overwrite = true)
  }
}

/**
 * Deliberately not on `check` or `test`.
 *
 * It builds a `.vsix` first, and paying twenty seconds of packaging on every `./gradlew test` to
 * check something only a release depends on is the wrong trade. It hangs off `publishExtension`
 * instead, which is the last moment it is free: after that the archive is on the Marketplace and
 * the version cannot be reused.
 */
val checkPackagedExtension = tasks.register<Exec>("checkPackagedExtension") {
  description = "Unpacks the .vsix and activates it, to prove the package is complete."
  group = LifecycleBasePlugin.VERIFICATION_GROUP

  dependsOn(unpackExtension)
  outputs.upToDateWhen { false }

  val bin = nodeBinDirectory
  val script = layout.buildDirectory.file("packaged-extension/extension/src/test/host/activate-in-a-stub-host.js")
  executable = File(bin.get(), if (onWindows) "node.exe" else "node").absolutePath
  args(script.get().asFile.absolutePath)
}

/**
 * Publishes to the Visual Studio Marketplace.
 *
 * No credential is written down here or anywhere else in this repository; [vsceAuth] decides which
 * one is used and where it comes from. Whichever it is, it belongs to an identity that has to be a
 * member of the publisher named by `publisher` in `package.json` - which is the one thing that
 * cannot be checked until the moment it fails.
 *
 * It never prompts. Left to itself `vsce` asks for a token on the terminal when it cannot find
 * one, and a task that prompts is a task that hangs in CI, so every mode passes something explicit.
 */
val publishExtension = tasks.register<Exec>("publishExtension") {
  description = "Publishes the extension to the Visual Studio Marketplace."
  group = "distribution"
  vsce("publish", "--packagePath", "build/vimperor-$extensionVersion.vsix")
  authenticate()
  // Nothing is published that has not been unpacked and run first. This is the last moment the
  // check is free; after it, the archive is on the Marketplace and the version cannot be reused.
  dependsOn(checkPackagedExtension)
}

