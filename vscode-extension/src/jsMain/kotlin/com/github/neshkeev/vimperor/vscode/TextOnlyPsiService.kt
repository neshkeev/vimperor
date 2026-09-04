/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimPsiService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange

/**
 * What this host can say about strings and comments, which is: something about strings, nothing
 * about comments.
 *
 * The engine asks these questions while scanning for brackets - `%`, `di(`, `ci{`, every block text
 * object. A bracket written inside a string or a comment is text rather than a bracket, and a scan
 * that counts it lands on the wrong pair. IdeaVim answers from IntelliJ's syntax tree, which knows
 * what a string is in the language at hand. So it named the interface after PSI and made the whole
 * thing IntelliJ's problem.
 *
 * VS Code has the same knowledge and will not part with it synchronously: an extension can ask a
 * language server for semantic tokens, but only over a promise, and these questions are asked once
 * per character in the middle of a keystroke. So this answers from the text itself.
 *
 * For strings that is less of a compromise than it sounds, because it is what the engine already
 * does for `i"` and `i'` - [VimSearchHelper.findBlockQuoteInLineRange] counts quotes along the
 * line - and answering the two questions differently would be worse than answering both roughly.
 * It gets `f("(", x)` right, which is the case that matters. It will be wrong about an apostrophe
 * in prose, as Vim itself is.
 *
 * For comments there is no equivalent: a comment is a `//` in one language, a `#` in another and a
 * `%` in a third, and guessing from the file extension would be a table of lies that grows forever.
 * Answering `null` is the same answer Vim gives with syntax off - brackets inside comments are
 * counted - and it is an honest one. It also means `getCommentBlockRange` stays unimplemented,
 * which is only used by Commentary, which this host does not have.
 */
object TextOnlyPsiService : VimPsiService {

  override fun getCommentAtPos(editor: VimEditor, pos: Int): Pair<TextRange, Pair<String, String>?>? = null

  override fun getDoubleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange? =
    quotedString(editor, pos, '"', isInner)

  override fun getSingleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange? =
    quotedString(editor, pos, '\'', isInner)

  /**
   * `isInner` here is not `i"` versus `a"`: it decides whether the quote characters themselves count
   * as part of the string, not whether trailing whitespace does. So the search is always an inner
   * one, and the flag only chooses whether the quotes are trimmed off the ends.
   */
  private fun quotedString(editor: VimEditor, pos: Int, quote: Char, isInner: Boolean): TextRange? {
    val quoted = injector.searchHelper.findBlockQuoteInLineRange(
      editor,
      pos,
      quote,
      isOuter = false,
      includeQuotes = true,
    ) ?: return null

    // findBlockQuoteInLineRange answers "the string at or after this offset", because that is what
    // `i"` wants: Vim's `i"` reaches forward to the next string on the line rather than failing.
    // It is the wrong answer to the question being asked here, which is whether *this* offset is
    // inside a string - and not merely a wrong answer. The bracket scan in `findBlock` skips a
    // string by jumping to the far end of the range it is given, so a range that begins after the
    // offset sends a backwards scan forwards, to the same bracket, for ever.
    if (pos < quoted.startOffset || pos >= quoted.endOffset) return null

    return if (isInner) TextRange(quoted.startOffset + 1, quoted.endOffset - 1) else quoted
  }
}
