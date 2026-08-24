/*
 * Copyright 2003-2026 The IdeaVim authors
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
    // VS Code loads extensions with `require`, so the output has to be CommonJS rather than ESM.
    useCommonJs()
    nodejs()
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
}

// `./gradlew test` matches by task NAME across projects, and a KMP module has no `test` task - so
// without this alias the gate documented in CLAUDE.md walks straight past this module, greenly.
// The same omission hid vim-engine's JS tests for a while; see the note on its `test` task.
tasks.register("test") {
  dependsOn("jsNodeTest")
  dependsOn(runInStubHost)
}
