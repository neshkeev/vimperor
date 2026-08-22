/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.parser.visitors

import com.maddyhome.idea.vim.common.TextRange
import org.antlr.v4.kotlinruntime.ParserRuleContext

/**
 * `start` and `stop` are `Token?` in the Kotlin runtime; `stop` is genuinely null for a context that
 * matched nothing. Java's platform types hid that, and this threw a NullPointerException there, so
 * the assertions keep the same behaviour rather than inventing a range for an empty match.
 */
fun ParserRuleContext.getTextRange(): TextRange {
  val startOffset = this.start!!.startIndex
  val endOffset = this.stop!!.stopIndex + 1
  return TextRange(startOffset, endOffset)
}
