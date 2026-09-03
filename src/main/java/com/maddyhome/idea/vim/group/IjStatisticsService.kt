/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.group

import com.maddyhome.idea.vim.api.VimStatistics
import com.maddyhome.idea.vim.statistic.ActionTracker
import com.maddyhome.idea.vim.statistic.VimscriptState

internal class IjStatisticsService : VimStatistics {

  override fun logTrackedAction(actionId: String) {
    ActionTracker.Util.logTrackedAction(actionId)
  }

  override fun logCopiedAction(actionId: String) {
    ActionTracker.Util.logCopiedAction(actionId)
  }

  override fun setIfLoopUsed(value: Boolean) {
    VimscriptState.Util.isLoopUsed = value
  }

  override fun setIfMapExprUsed(value: Boolean) {
    VimscriptState.Util.isMapExprUsed = value
  }

  override fun setIfFunctionCallUsed(value: Boolean) {
    VimscriptState.Util.isFunctionCallUsed = value
  }

  override fun setIfFunctionDeclarationUsed(value: Boolean) {
    VimscriptState.Util.isFunctionDeclarationUsed = value
  }

  override fun setIfIfUsed(value: Boolean) {
    VimscriptState.Util.isIfUsed = value
  }

  /**
   * Set by `has('ide')`, which is how an IDE-aware `.ideavimrc` announces itself.
   *
   * `has()` used to live in this module and set the flag directly. It is the engine's now, so that
   * the VS Code host has it too, and this is the one thing the move left behind.
   */
  override fun setIdeSpecificConfigurationUsed(value: Boolean) {
    VimscriptState.Util.isIDESpecificConfigurationUsed = value
  }

  override fun addExtensionEnabledWithPlug(extension: String) {
    VimscriptState.Util.extensionsEnabledWithPlug.add(extension)
  }

  override fun addSourcedFile(path: String) {
    VimscriptState.Util.sourcedFiles.add(path)
  }
}
