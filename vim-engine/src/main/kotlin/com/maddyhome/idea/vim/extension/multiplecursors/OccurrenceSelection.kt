/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.helper.endOffsetInclusive
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.state.mode.SelectionType

/**
 * The selection helpers both multiple-cursor extensions are built out of.
 *
 * **A caret is a value here, not a handle.** `vimSetSelection` may move the caret, and a moved caret
 * is a *new* `VimCaret` on this side - the one in hand still describes where it was. So everything
 * below that changes a selection reads the result back off the editor, by the offset it selected
 * from, rather than off the caret it was given. That is the single difference from IdeaVim's copies
 * of these, where a `Caret` is a live object and `caret.selectedRange` answers afterwards.
 */

/** Selects the occurrence of [text] at [offset], with the caret at its end. */
internal fun selectOccurrence(editor: VimEditor, caret: VimCaret, offset: Int, text: String): TextRange? {
  if (text.isEmpty()) return null
  return selectRange(editor, caret, TextRange(offset, offset + text.length))
}

/** Selects [range], with the caret at its end. */
internal fun selectRange(editor: VimEditor, caret: VimCaret, range: TextRange): TextRange? {
  caret.vimSetSelection(range.startOffset, range.endOffset - 1, true)
  injector.scroll.scrollCaretIntoView(editor)
  return editor.selectionStartingAt(range.startOffset)
}

/** Enters Visual mode and selects the word under the caret, or answers null if there is not one. */
internal fun selectWordUnderCaret(editor: VimEditor, caret: VimCaret): TextRange? {
  val word = wordRange(editor, caret) ?: return null
  enterCharacterwiseVisualMode(editor)
  caret.vimSetSelection(word.startOffset, word.endOffsetInclusive, true)
  return editor.selectionStartingAt(word.startOffset)
}

internal fun wordUnderCaret(editor: VimEditor, caret: VimCaret): String? =
  wordRange(editor, caret)?.let { editor.getText(it) }

/** The word under the caret, or null when the caret is not on one. */
internal fun wordRange(editor: VimEditor, caret: VimCaret): TextRange? {
  val word = injector.searchHelper.findWordAtOrFollowingCursor(editor, caret, isBigWord = false) ?: return null
  return if (word.startOffset > caret.offset) null else word
}

/** What the caret has selected, or null when it has selected nothing. */
internal fun VimCaret.selectedText(editor: VimEditor): String? =
  if (!hasSelection()) null else editor.getText(TextRange(selectionStart, selectionEnd))

internal val VimCaret.selectedRange: TextRange get() = TextRange(selectionStart, selectionEnd)

internal fun VimEditor.isSelectedByAnyCaret(offset: Int): Boolean =
  carets().any { it.selectionStart == offset }

/** The selection of whichever caret now selects from [offset], which is how a change is read back. */
private fun VimEditor.selectionStartingAt(offset: Int): TextRange? =
  carets().firstOrNull { it.selectionStart == offset }?.selectedRange

internal fun enterCharacterwiseVisualMode(editor: VimEditor) {
  injector.visualMotionGroup.enterVisualMode(editor, SelectionType.CHARACTER_WISE)
  // The key handler has to know about the mode before the next key arrives.
  KeyHandler.getInstance().reset(editor)
}

internal fun leaveVisualMode(editor: VimEditor) {
  editor.exitVisualMode()
  KeyHandler.getInstance().reset(editor)
}

internal fun showNoMoreMatches() {
  injector.messages.showStatusBarMessage(null, injector.messages.message("multiple-cursors.message.no.more.matches"))
}
