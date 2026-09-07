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
    // except for `org.jetbrains.annotations`, which the jvm/ and js/ trees shim as expect/actual.
    val commonMain = getByName("commonMain") {
      kotlin.setSrcDirs(listOf("src/main/kotlin"))
    }
    val jvmMain = getByName("jvmMain") {
      kotlin.setSrcDirs(listOf("jvm/src/main/kotlin"))
      dependencies {
        compileOnly("org.jetbrains:annotations:26.1.0")
        // gradle.properties sets kotlin.stdlib.default.dependency=false, so this has to be asked
        // for. It used to arrive transitively through a kotlinx-coroutines dependency that no
        // source in this repository imports; removing that dead entry is what exposed it. Only
        // the JVM target needs it - the JS compilation resolves the stdlib on its own.
        compileOnly(kotlin("stdlib"))
      }
    }
    val jsMain = getByName("jsMain") {
      kotlin.setSrcDirs(listOf("js/src/main/kotlin"))
    }
  }
}
