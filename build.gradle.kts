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
  kotlin("jvm") version "2.4.20" apply false
  kotlin("multiplatform") version "2.4.20" apply false

  // OWASP Dependency-Check, applied here and not `apply false`, because `dependencyCheckAggregate`
  // is a root task that walks the subprojects: this is the one plugin the root project runs rather
  // than only supplying a version for. See the `dependencyCheck` block below.
  id("org.owasp.dependencycheck") version "13.0.0"
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

/**
 * OWASP Dependency-Check: known CVEs in what this build resolves.
 *
 * ## What it does and does not cover
 *
 * The configurations of all five modules, which is the *build's* supply chain - ANTLR, kotlinx,
 * JUnit, mockito, the Kotlin stdlib. It is worth being clear that this is not the same as scanning
 * what a user installs: the `.vsix` ships Kotlin/JS output and a handful of `.js` runtimes, and a
 * CVE in mockito cannot reach it. Build-time compromise is a real thing to watch for and this
 * watches for it; the published artifact needs a different tool.
 *
 * ## The NVD API key
 *
 * `nvd.apiKey` is documented as optional and is not, in practice. Without one Dependency-Check
 * waits 8000ms between NVD calls instead of 3500, and the first run makes thousands of them - the
 * difference between minutes and most of an afternoon. The workflow passes `NVD_API_KEY` when the
 * secret exists and the build reads it from the environment, so a machine without one still works,
 * slowly. Keys are free: https://nvd.nist.gov/developers/request-an-api-key
 *
 * ## Failing
 *
 * `failBuildOnCVSS = 7` is High and above. It fails the *scan*, which is its own scheduled
 * workflow, not the release: a CVE published upstream overnight should turn a dashboard red, not
 * block a release that has nothing to do with it.
 *
 * ## It cannot run the way the rest of the build runs
 *
 * `./gradlew dependencyCheckAggregate --no-configuration-cache`, and without `--parallel`. Both are
 * required and they fail differently, which is worth knowing before anyone "tidies" the workflow:
 *
 *  - This build turns the configuration cache on in gradle.properties and the plugin does not
 *    support it. `Aggregate` holds a `Project` and calls `Task.project` while executing, so storing
 *    the entry fails with "cannot serialize object of type 'DefaultProject'".
 *  - With the configuration cache off it still fails under `--parallel`, because the task resolves
 *    other projects' configurations at execution time: "Resolution of the configuration
 *    ':annotation-processors:annotationProcessor' was attempted without an exclusive lock."
 *
 * Neither flag is given up anywhere else; the scan is the exception and its workflow says so.
 */
dependencyCheck {
  formats = listOf("HTML", "JSON")
  failBuildOnCVSS = 7.0f

  // A classpath Kotlin's Gradle plugin declares for a feature this build does not use, and cannot
  // update if it wanted to.
  //
  // KGP 2.4 added ABI validation - a dump of every public declaration, compared against a baseline
  // so a binary-compatibility break is caught. Nothing here enables it, and nothing here has a
  // reason to: every dependency in this repository is a project dependency, so `vim-engine`, `api`
  // and `vscode-extension` are compiled together on every build and a shape change is a compile
  // error in the same command. There is no separately compiled consumer to break. The publishing
  // block in vim-engine is IdeaVim's and is inert - `uploadUrl` is unset, which is what disables
  // it - and the shipped artifact is one JS bundle, linked at bundle time.
  //
  // KGP declares `kotlinAbiValidationCompatClasspath` in all five modules regardless, pinned
  // `{strictly 2.4.0}` because the point of a compat classpath is to hold an older compiler. So it
  // carried a whole Kotlin 2.4.0 toolchain - build-tools, compiler-embeddable, daemon, stdlib -
  // and CVE-2026-53914 with it, which is nine of the twelve findings the 2.4.20 bump left behind.
  // The `strictly` is not something a resolution strategy should be reaching around: it is KGP's
  // pin, on KGP's own classpath, and it moves when KGP moves it.
  //
  // The narrow reading is the only one that would justify this: skip a configuration no task
  // resolves. `kotlinCompilerClasspath` - the one that actually runs the compiler - is still
  // scanned, and still resolves to 2.4.20.
  skipConfigurations = listOf("kotlinAbiValidationCompatClasspath")

  // No `data { directory = ... }`. It was here, pointing at `.dependency-check-data` in the
  // repository, and the plugin ignored it: both the `data.directory = ...` and the `data { }` forms
  // compile, neither takes effect, and the database goes to `~/.gradle/dependency-check-data`
  // regardless. Verified by watching for the directory during a run - it never appears - and by
  // finding 98 MB under `~/.gradle` afterwards.
  //
  // That was not a cosmetic problem. CI cached the path this asked for, which never existed, so
  // `actions/cache` saved nothing ("Path Validation Error: Path(s) specified in the action for
  // caching do(es) not exist") and every run paid for a full NVD download from cold. The workflow
  // caches `~/.gradle/dependency-check-data` now, which is where the data actually is.
  //
  // `gradle/actions/setup-gradle` does not collide with it: that caches `caches` and
  // `notifications` under `~/.gradle` and nothing else.

  // Nothing here is an npm project. `vscode-extension/package.json` declares no dependencies and no
  // devDependencies - it is a VS Code manifest, and what it ships is compiled Kotlin - so the Node
  // analyzers have nothing to find and say so at length on every run: "the node_modules directory
  // does not exist", "No lock file exists - this will result in false negatives". A check that can
  // only report its own absence is noise.
  analyzers {
    nodeEnabled = false
    nodeAuditEnabled = false
  }

  nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
}
