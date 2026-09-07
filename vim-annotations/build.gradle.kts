/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

plugins {
  kotlin("multiplatform")
}

group = "com.intellij"
version = "SNAPSHOT"

repositories {
  maven { url = uri("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2") }
}

// The `@CommandOrMotion`, `@ExCommand` and `@VimscriptFunction` declarations, split out of
// :annotation-processors so that engine code can carry them in commonMain.
//
// The processors themselves cannot come along - they need KSP's symbol-processing-api, which is
// JVM-only - but the annotations are plain Kotlin with no dependencies at all. Leaving them in the
// same module as the processors made every annotated engine file JVM-bound, which was 1845 of the
// 2153 errors the first JS compile produced.
kotlin {
  jvm()
  js(IR) {
    nodejs()
    binaries.library()
  }

  sourceSets {
    val commonMain by getting {
      kotlin.setSrcDirs(listOf("src/main/kotlin"))
      dependencies {
        // gradle.properties sets kotlin.stdlib.default.dependency=false, so every module declares
        // stdlib itself. compileOnly matches the rest of the project: the IDE provides it at
        // runtime, and it must not be bundled into the plugin distribution.
        compileOnly(kotlin("stdlib"))
      }
    }
  }
}
