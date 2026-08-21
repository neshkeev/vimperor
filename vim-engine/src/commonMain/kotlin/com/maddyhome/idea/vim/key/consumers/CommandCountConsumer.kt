/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.key.consumers

import com.maddyhome.idea.vim.KeyProcessResult
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.diagnostic.trace
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.key.KeyConsumer
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.state.KeyHandlerState
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * Key consumer to handle digits typed while building a command
 *
 * This consumer only handles digits in NVO mode. It does not need to handle escape or cancel keys.
 */
internal class CommandCountConsumer : KeyConsumer {
  private companion object {
    private val logger = vimLogger<CommandCountConsumer>()
  }

  override fun isApplicable(
    key: VimKeyStroke,
    editor: VimEditor,
    keySource: KeySource,
    keyProcessResultBuilder: KeyProcessResult.KeyProcessResultBuilder,
  ): Boolean {
    val chKey: Char = if (key.keyChar == VimKeyCodes.CHAR_UNDEFINED) 0.toChar() else key.keyChar
    return isCommandCountKey(chKey, keyProcessResultBuilder.state)
  }

  override fun consumeKey(
    key: VimKeyStroke,
    editor: VimEditor,
    keySource: KeySource,
    keyProcessResultBuilder: KeyProcessResult.KeyProcessResultBuilder,
  ): Boolean {
    logger.trace { "Entered CommandCountConsumer" }
    keyProcessResultBuilder.state.commandBuilder.addCountCharacter(key)
    return true
  }

  private fun isCommandCountKey(chKey: Char, keyState: KeyHandlerState): Boolean {
    val editorState = injector.vimState
    val commandBuilder = keyState.commandBuilder

    // Make sure to avoid handling '0' as the start of a count.
    if (chKey.isDigit() && !(chKey == '0' && !commandBuilder.hasCountCharacters())
      && (editorState.mode is Mode.NORMAL || editorState.mode is Mode.VISUAL || editorState.mode is Mode.OP_PENDING)
      && commandBuilder.isExpectingCount
    ) {
      logger.debug("This is a command count key")
      return true
    }

    logger.debug("This is NOT a command count key")
    return false
  }
}
