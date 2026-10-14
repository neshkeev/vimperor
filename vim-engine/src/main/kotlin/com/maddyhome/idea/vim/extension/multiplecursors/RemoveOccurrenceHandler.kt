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
import com.maddyhome.idea.vim.api.injector

/** Removes the last added cursor. Removing the only one leaves Visual mode. */
internal class RemoveOccurrenceHandler : WriteActionHandler() {

  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext): Boolean {
    val caret = editor.primaryCaret()
    if (caret.selectedText(editor) == null) return false

    if (editor.carets().size > 1) {
      editor.removeCaret(caret)
      // The search carries on from whichever cursor is the last one now.
      val lastOccurrence = editor.primaryCaret().selectedRange
      editor.occurrenceSearch = editor.occurrenceSearch?.copy(lastOccurrence = lastOccurrence)
    } else {
      leaveVisualMode(editor)
    }
    injector.scroll.scrollCaretIntoView(editor)
    return true
  }
}
