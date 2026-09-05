/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.common.TextRange

interface VimPsiService {
  /**
   * @return triple of comment range, comment prefix, comment suffix or null if there is no comment at the given position
   */
  fun getCommentAtPos(editor: VimEditor, pos: Int): Pair<TextRange, Pair<String, String>?>?

  /**
   * @param isInner A flag indicating whether the start and end quote characters should be considered part of the string:
   *                - If set to true, only the text between the quote characters is included in the range.
   *                - If set to false, the quote characters at the boundaries are included as part of the string range.
   *
   * NOTE: Regardless of the [isInner] value, a TextRange will be returned if the caret is positioned on a quote character.
   */
  fun getDoubleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange?

  /**
   * @param isInner A flag indicating whether the start and end quote characters should be considered part of the string:
   *                - If set to true, only the text between the quote characters is included in the range.
   *                - If set to false, the quote characters at the boundaries are included as part of the string range.
   *
   * NOTE: Regardless of the [isInner] value, a TextRange will be returned if the caret is positioned on a quote character.
   */
  fun getSingleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange?

  /**
   * Finds the range of contiguous comment lines around the given [cursorLine].
   * Walks up and down from the cursor, checking each line for comment-only content via PSI.
   *
   * @return a [TextRange] covering the full comment block (line-wise), or null if the cursor is not on a comment line.
   */
  fun getCommentBlockRange(editor: VimEditor, cursorLine: Int): TextRange? = null

  /**
   * The function or method that encloses [offset], or null if the host cannot say.
   *
   * This is what `vim-textobj-function`'s `af`, `aF` and `if` select, and the three ranges are the
   * three of them: `af` is the definition without its doc comment, `aF` includes it, and `if` is the
   * body. Answering needs to know what a function *is* in the language at hand, which is a fact
   * about the file that only a host has - the same reason [getCommentAtPos] is here.
   *
   * A host that cannot answer says so, and the text objects then do nothing rather than guessing.
   */
  fun getMethodRanges(editor: VimEditor, offset: Int): MethodRanges? = null

  /**
   * The class that encloses [offset], or null if the host cannot say. `vim-textobj-python`'s `ac`
   * and `ic`. See [getMethodRanges].
   */
  fun getClassRange(editor: VimEditor, offset: Int): TextRange? = null
}

/**
 * Where a function begins and ends, in the three ways a text object asks about it.
 *
 * [fullStart] takes the doc comment with it and [definitionStart] does not; [body] is what is
 * between the braces, and is null for a declaration with no body.
 */
data class MethodRanges(
  val fullStart: Int,
  val definitionStart: Int,
  val end: Int,
  val body: Pair<Int, Int>?,
)
