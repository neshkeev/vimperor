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
import com.maddyhome.idea.vim.state.mode.inVisualMode

/** Adds a cursor at every occurrence of the word under the caret, or of the selection in Visual mode. */
internal class AllOccurrencesHandler(
  private val wholeWord: Boolean,
  private val caseSensitivity: CaseSensitivity,
) : WriteActionHandler() {

  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext): Boolean {
    if (editor.carets().size > 1) return false

    val wasVisual = editor.inVisualMode
    val text = if (wasVisual) {
      editor.primaryCaret().selectedText(editor)
    } else {
      wordUnderCaret(editor, editor.primaryCaret())
    } ?: return false

    if (!wasVisual) {
      enterCharacterwiseVisualMode(editor)
    }

    for (occurrence in findAllOccurrences(editor, text, wholeWord, caseSensitivity.ignoresCase(text))) {
      val primary = editor.primaryCaret()
      if (occurrence.contains(primary.offset)) {
        primary.moveToOffset(occurrence.startOffset)
        selectOccurrence(editor, editor.primaryCaret(), occurrence.startOffset, text)
      } else {
        val caret = editor.addCaret(occurrence.startOffset) ?: return false
        selectOccurrence(editor, caret, occurrence.startOffset, text)
      }
    }
    return true
  }
}
