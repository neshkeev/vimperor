/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

interface VimStatistics {
  fun logTrackedAction(actionId: String)
  fun logCopiedAction(actionId: String)
  fun setIfIfUsed(value: Boolean)
  fun setIfFunctionCallUsed(value: Boolean)
  fun setIfFunctionDeclarationUsed(value: Boolean)
  fun setIfLoopUsed(value: Boolean)
  fun setIfMapExprUsed(value: Boolean)
  fun addExtensionEnabledWithPlug(extension: String)
  fun addSourcedFile(path: String)

  /**
   * Records that the configuration asked `has('ide')`, which is how IdeaVim counts IDE-aware
   * `.ideavimrc` files.
   *
   * Defaulted so that only a host which reports statistics has to implement it. `has()` moved into
   * the engine and this is the one thing the IntelliJ version of it did that the engine cannot.
   */
  fun setIdeSpecificConfigurationUsed(value: Boolean) {}
}
