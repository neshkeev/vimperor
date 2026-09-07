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
  // PHASE 4 PROBE: does this module compile for JS? Nothing consumes the JS artifact yet.
  js(IR) {
    nodejs()
    binaries.library()
  }

  sourceSets {
    // Maven layout: src/main/kotlin is the common code, and a target with code of its own gets
    // a directory named for it inside src/main. Phase 3 / W6 left this module platform-neutral
    // except for `org.jetbrains.annotations`, which src/main/{jvm,js} shim as expect/actual.
    val commonMain by getting {
      kotlin.setSrcDirs(listOf("src/main/kotlin"))
    }
    val jvmMain by getting {
      kotlin.setSrcDirs(listOf("src/main/jvm"))
      dependencies {
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
      }
    }
    val jsMain by getting {
      kotlin.setSrcDirs(listOf("src/main/js"))
    }
  }
}
