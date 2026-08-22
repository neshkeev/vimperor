/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.key.VimKeyCodes

actual fun charCategoryOf(codepoint: Int): CharCategory =
  CharCategory.valueOf(Character.getType(codepoint))

/** Unchanged from where this lived in `VimChangeGroupBase`, so the JVM answer is exactly as before. */
actual fun isPrintableChar(c: Char): Boolean {
  val block = Character.UnicodeBlock.of(c)
  return !Character.isISOControl(c) &&
    (c != VimKeyCodes.CHAR_UNDEFINED) &&
    (block != null) &&
    block !== Character.UnicodeBlock.SPECIALS
}
