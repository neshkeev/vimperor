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

// Every Kotlin/JS module is named `<rootProject.name>-<module>.js`, and those files ship inside the
// `.vsix` - so this string is not only a label in Gradle's output, it is what a user finds when they
// unpack the extension they installed. It was `IdeaVIM`, which put `IdeaVIM-api.js`,
// `IdeaVIM-vim-annotations.js` and `IdeaVIM-vim-engine.js` in there.
//
// Renaming it covers a module nobody has added yet, which is why it is done here rather than with an
// `outputModuleName` in each build file. `vscode-extension` still sets one, because its bundle is
// `vimperor.js` rather than `vimperor-vscode-extension.js` - `package.json` names it as `main`.
//
// The engine's own code, and its attribution, are untouched: AUTHORS.md, ThirdPartyLicenses.md, the
// README and the copyright header on nearly every file say whose work it is.
rootProject.name = "vimperor"

include("vim-engine")
include("vscode-extension")
include("annotation-processors")
include("vim-annotations")
include("api")
