/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

plugins {
  kotlin("multiplatform")
}

repositories {
  maven { url = uri("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2") }
}

// PHASE 1 TASK 1 PROBE: does a KMP jvm target compile Java sources without the
// `java` plugin? Sources left in place; only the build wiring changed.
kotlin {
  jvm()

  sourceSets {
    val jvmMain by getting {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
      }
    }
  }
}
