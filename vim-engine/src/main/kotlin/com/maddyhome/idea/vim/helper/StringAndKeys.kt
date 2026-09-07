/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke


fun VimKeyStroke.isCloseKeyStroke(): Boolean {
  return keyCode == VimKeyCodes.VK_ESCAPE ||
    keyChar.code == VimKeyCodes.VK_ESCAPE ||
    keyCode == VimKeyCodes.VK_C && modifiers and VimKeyCodes.CTRL_DOWN_MASK != 0 ||
    keyCode == '['.code && modifiers and VimKeyCodes.CTRL_DOWN_MASK != 0
}

/**
 * Returns true if this character would be matched as a command-line action (close or execute) rather than text input
 * when re-injected through the key handler in CMD_LINE mode.
 *
 * Escape closes the command line, Enter/CR executes it.
 */
fun Char.isCommandLineActionChar(): Boolean {
  return this == '\u001B' || this == '\n' || this == '\r'
}
