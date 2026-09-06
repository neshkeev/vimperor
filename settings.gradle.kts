/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

pluginManagement {
  repositories {
    maven {
      url = uri("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
    maven {
      url = uri("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
    }
    maven {
      url = uri("https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

// Automatically download JDKs from Foojay API when required toolchain is not installed locally
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "IdeaVIM"

include("vim-engine")
include("vscode-extension")
include("annotation-processors")
include("vim-annotations")
include("api")
