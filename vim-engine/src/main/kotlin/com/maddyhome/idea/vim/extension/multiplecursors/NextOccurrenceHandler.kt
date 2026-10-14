/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * What happens to a selection that is not the last added occurrence - one the user made, say.
 */
internal enum class NewSelection {
  /**
   * vim-multiple-cursors: a selection over several lines becomes a cursor on each of them, and
   * anything else adds the next occurrence.
   */
  SPLIT_LINES_OR_ADD_NEXT,

  /** `visual-multi`'s Find Subword Under: the selection becomes the first region. */
  START_SEARCH,

  /** `visual-multi`'s Find Next. */
  ADD_NEXT,
}

/**
 * In Normal mode selects the word under the caret; in Visual mode adds a cursor at the next
 * occurrence of the selection, or at the previous one when the direction is backwards.
 *
 * @param wholeWord whether the word selected in Normal mode is searched with word boundaries. In
 *   Visual mode the flag of the search already running is used instead.
 */
internal class NextOccurrenceHandler(
  private val wholeWord: Boolean,
  private val caseSensitivity: CaseSensitivity,
  override val acceptsCount: Boolean = false,
  private val newSelection: NewSelection = NewSelection.SPLIT_LINES_OR_ADD_NEXT,
  private val direction: Direction = Direction.FORWARDS,
) : WriteActionHandler() {

  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext): Boolean =
    if (editor.inVisualMode) addNextOccurrence(editor) else startWithWordUnderCaret(editor)

  private fun startWithWordUnderCaret(editor: VimEditor): Boolean {
    if (editor.carets().size > 1) return false

    editor.occurrenceSearch = null
    val word = selectWordUnderCaret(editor, editor.primaryCaret()) ?: return false
    startOccurrenceSearch(editor, word, wholeWord, caseSensitivity)
    return true
  }

  private fun addNextOccurrence(editor: VimEditor): Boolean {
    val carets = editor.carets()
    if (carets.any { it.selectedText(editor) == null }) return false
    if (!isLastOccurrenceSelected(editor)) {
      when (newSelection) {
        NewSelection.SPLIT_LINES_OR_ADD_NEXT -> if (splitMultilineSelections(editor)) return true
        NewSelection.START_SEARCH -> {
          val selection = editor.primaryCaret().selectedRange
          startOccurrenceSearch(editor, selection, wholeWord = false, caseSensitivity)
          return true
        }

        NewSelection.ADD_NEXT -> Unit
      }
    }
    // Cursors added some other way may be selecting different texts, and then this is not a session
    // of this extension's making.
    if (!selectsSameText(editor, carets)) return false

    val search = continueOrStartSearch(editor).copy(direction = direction)
    editor.occurrenceSearch = search
    val current = search.lastOccurrence
    val pattern = editor.getText(current)

    val nextOffset = findOccurrence(editor, current, pattern, search.wholeWord, search.ignoreCase, direction)
    if (nextOffset == null || editor.isSelectedByAnyCaret(nextOffset)) {
      showNoMoreMatches()
      return false
    }

    val caret = editor.addCaret(nextOffset) ?: return false
    val occurrence = selectOccurrence(editor, caret, nextOffset, pattern) ?: return false
    // The cursor just added is the current one, and the next search has to continue from *it*.
    // IntelliJ gets this from `addCaret(position, true)`; this host has to ask, and the asking is
    // what `makePrimaryCaret` is for. Without it the primary stayed on the first occurrence, the
    // check below saw the recorded occurrence and the primary's selection disagree, started a fresh
    // search from the first occurrence, found the second - already selected - and reported no more
    // matches. `foo bar foo baz foo` stopped at two cursors however many times `<C-n>` was pressed.
    editor.makePrimaryCaret(editor.carets().firstOrNull { it.selectionStart == nextOffset } ?: caret)
    editor.occurrenceSearch = search.copy(lastOccurrence = occurrence)
    return true
  }

  private fun isLastOccurrenceSelected(editor: VimEditor): Boolean =
    editor.occurrenceSearch?.lastOccurrence == editor.primaryCaret().selectedRange

  /** A new search - for a selection the user made, say - is one without word boundaries. */
  private fun continueOrStartSearch(editor: VimEditor): OccurrenceSearch {
    val selection = editor.primaryCaret().selectedRange
    return editor.occurrenceSearch?.takeIf { it.lastOccurrence == selection }
      ?: startOccurrenceSearch(editor, selection, wholeWord = false, caseSensitivity)
  }

  private fun selectsSameText(editor: VimEditor, carets: List<VimCaret>): Boolean {
    val ignoreCase = editor.occurrenceSearch?.ignoreCase == true
    return carets.mapNotNull { it.selectedText(editor) }
      .distinctBy { if (ignoreCase) it.lowercase() else it }
      .size <= 1
  }

  /**
   * Turns each selection spanning several lines into a cursor at the start column of each of its
   * lines, and answers whether there was any such selection.
   */
  private fun splitMultilineSelections(editor: VimEditor): Boolean {
    val newOffsets = mutableListOf<Int>()
    for (caret in editor.carets()) {
      val offsets = offsetsOnFollowingLines(editor, caret)
      if (offsets.isNotEmpty()) {
        caret.moveToOffset(caret.selectionStart)
      }
      newOffsets.addAll(offsets)
    }
    if (newOffsets.isEmpty()) return false

    leaveVisualMode(editor)
    newOffsets.forEach { editor.addCaret(it) }
    return true
  }

  private fun offsetsOnFollowingLines(editor: VimEditor, caret: VimCaret): List<Int> {
    // A selection ending with a newline ends on the line after the one it covers.
    val endsWithNewLine = caret.selectedText(editor)?.endsWith('\n') == true
    val selectionEnd = if (endsWithNewLine) caret.selectionEnd - 1 else caret.selectionEnd
    val start = editor.offsetToBufferPosition(caret.selectionStart)
    val followingLines = editor.offsetToBufferPosition(selectionEnd).line - start.line

    return (1..followingLines).map { editor.bufferPositionToOffset(BufferPosition(start.line + it, start.column)) }
  }
}
