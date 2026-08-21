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

kotlin {
  jvm()

  sourceSets {
    val commonMain by getting {
      // Phase 3 / W6. The whole module is platform-neutral now: its only JVM dependency was
      // `org.jetbrains.annotations`, which src/commonMain + src/jvmMain shim as expect/actual.
      // Sources stay in src/main/kotlin rather than moving, so the diff stays reviewable.
      kotlin.srcDir("src/main/kotlin")
    }
    val jvmMain by getting {
      dependencies {
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
      }
    }
  }
}
