/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.Direction

/**
 * Moves the last added cursor to the next occurrence, or to the previous one when the last search
 * went backwards.
 */
internal class SkipOccurrenceHandler(private val caseSensitivity: CaseSensitivity) : WriteActionHandler() {

  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext): Boolean {
    val text = editor.primaryCaret().selectedText(editor) ?: return false

    val search = editor.occurrenceSearch
    val wholeWord = search?.wholeWord ?: false
    val ignoreCase = search?.ignoreCase ?: caseSensitivity.ignoresCase(text)
    val direction = search?.direction ?: Direction.FORWARDS

    val current = editor.primaryCaret().selectedRange
    val nextOffset = findOccurrence(editor, current, text, wholeWord, ignoreCase, direction) ?: return false
    if (editor.isSelectedByAnyCaret(nextOffset)) {
      showNoMoreMatches()
      return false
    }

    editor.primaryCaret().moveToOffset(nextOffset)
    val occurrence = selectOccurrence(editor, editor.primaryCaret(), nextOffset, text)
    editor.occurrenceSearch = search?.copy(lastOccurrence = occurrence ?: return false)
    return true
  }
}
