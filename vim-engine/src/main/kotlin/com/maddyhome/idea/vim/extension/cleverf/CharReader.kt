/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke

/**
 * Decides what character a key the user typed means to search for. A key from
 * `g:clever_f_repeat_last_char_inputs` (`<CR>` by default) stands for the last searched character.
 *
 * **It decides and does not read**, which is the one structural difference from IdeaVim's version of this file.
 * There the whole thing is `inputKeyStroke`, which blocks: IntelliJ answers it by pumping a nested AWT event loop,
 * so the character is in hand before the handler returns. A runtime with one thread has no nested loop, so the
 * reading is [FindHandler]'s and happens through `readKeys`, a keystroke later; what is left here is the question
 * "and what does that key mean", which is the part that has a `g:clever_f_*` variable behind it.
 */
internal class CharReader(private val session: CleverFSession) {
  /** The character to search for, or null when there is nothing to repeat. */
  fun charOf(key: VimKeyStroke): Char? = when {
    isRepeatLastCharInput(key) -> session.lastChar ?: null.also { injector.messages.indicateError() }
    else -> key.keyChar.takeUnless { it == VimKeyCodes.CHAR_UNDEFINED }
  }

  private fun isRepeatLastCharInput(key: VimKeyStroke): Boolean {
    val inputs = CleverFSettings.repeatLastCharInputs ?: return isEnter(key)
    return inputs.any { input -> if (input == "\r" || input == "\n") isEnter(key) else isSameKey(input, key) }
  }

  private fun isSameKey(input: String, key: VimKeyStroke): Boolean =
    injector.parser.parseKeys(input).singleOrNull() == key || input == key.keyChar.toString()

  private fun isEnter(key: VimKeyStroke): Boolean =
    key.keyCode == VimKeyCodes.VK_ENTER || key.keyChar == '\n' || key.keyChar == '\r'
}
