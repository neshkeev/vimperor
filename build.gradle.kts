/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/**
 * The root project builds nothing.
 *
 * It was the IdeaVim plugin - 615 lines of `org.jetbrains.intellij.platform`, a `runIde`, a
 * changelog, a YouTrack release script and a plugin descriptor. All of that went with the plugin.
 * What is left is a place for the modules to hang off and two things they share: the Java version
 * this fork is built with, and the toolchain their tests run on.
 *
 * `src/test` is still here and is no longer compiled. It holds IdeaVim's 11,727 tests, which cannot
 * run without the plugin, and the VS Code host mines 2,423 replayed fixtures out of them as *text*.
 * That is why the directory survives the module that owned it: it is data now, and
 * `vscode-extension` declares it as a task input.
 */

/**
 * Declared here and applied nowhere: this project builds nothing, but the modules ask for
 * `kotlin("jvm")` and `kotlin("multiplatform")` without a version and the root is where a version
 * has to be on the classpath for them to resolve.
 */
plugins {
  kotlin("jvm") version "2.3.20" apply false
  kotlin("multiplatform") version "2.3.20" apply false
}

val javaVersion = project.property("javaVersion") as String

// The toolchain check, which used to read `javaToolchains` - an extension the `java` plugin brings,
// and this project no longer applies one. `JavaVersion.current()` is the same question asked of the
// JVM that is running Gradle, which is what the message was about anyway.
val currentJavaVersion = JavaVersion.current().majorVersion
if (currentJavaVersion != javaVersion) {
  throw GradleException(
    """
    Incorrect java version used for building.
    Vimperor uses java version $javaVersion, but the current java version is $currentJavaVersion.
    If IntelliJ IDEA is used, change the setting in "Settings | Build, Execution, Deployment | Build Tools | Gradle"
    If build is run from the terminal, set JAVA_HOME environment variable to the correct java version.
    """.trimIndent(),
  )
}

// Resolved once, out here: inside the `allprojects` closure `javaVersion` binds to something else.
val toolchainJavaVersion = JavaLanguageVersion.of(javaVersion.toInt())

allprojects {
  afterEvaluate {
    // Only where a toolchain exists to ask for - the JS-only modules have none.
    val toolchains = project.extensions.findByType<JavaToolchainService>() ?: return@afterEvaluate
    tasks.withType<Test>().configureEach {
      javaLauncher.set(toolchains.launcherFor { languageVersion.set(toolchainJavaVersion) })
    }
  }
}
