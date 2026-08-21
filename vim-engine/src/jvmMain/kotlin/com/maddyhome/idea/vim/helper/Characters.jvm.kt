/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

actual fun charCategoryOf(codepoint: Int): CharCategory =
  CharCategory.valueOf(Character.getType(codepoint))

actual fun isRightToLeft(codepoint: Int): Boolean {
  val directionality = Character.getDirectionality(codepoint)
  return directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
    directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
    directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
    directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
}
