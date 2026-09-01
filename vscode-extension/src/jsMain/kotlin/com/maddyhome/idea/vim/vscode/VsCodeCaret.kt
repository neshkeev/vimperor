/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.group.visual.VisualChange
import com.maddyhome.idea.vim.api.LocalMarkStorage
import com.maddyhome.idea.vim.api.CaretRegisterStorage
import com.maddyhome.idea.vim.api.CaretRegisterStorageBase
import com.maddyhome.idea.vim.api.SelectionInfo
import com.maddyhome.idea.vim.state.mode.SelectionType

/**
 * One of VS Code's cursors, as the engine sees it.
 *
 * VS Code has no separate notion of a caret: it has selections, and a caret is one whose two ends
 * coincide. The offset lives here rather than in VS Code because the engine moves carets against
 * text VS Code has not been told about yet - [VsCodeEditor.flush] pushes both across together, text
 * first.
 */
class VsCodeCaret(
  private val vimEditor: VsCodeEditor,
  offset: Int,
  override val isPrimary: Boolean,
) : VimCaret {

  /** Mutable, because editing and motion both move it, and this caret is never replaced. */
  override var offset: Int = offset
    private set

  override val editor: VimEditor get() = vimEditor

  override fun moveToOffsetNative(offset: Int) {
    this.offset = offset
  }

  /**
   * IntelliJ's inlays are inline hints that occupy visual columns; VS Code's decorations do not
   * take part in offsets at all, so this is a plain move. The interface returns a caret because an
   * implementation may be immutable - this one moves itself and hands itself back.
   */
  override fun moveToInlayAwareOffset(newOffset: Int): VimCaret {
    offset = newOffset
    return this
  }

  override fun moveToBufferPosition(position: BufferPosition) {
    offset = vimEditor.bufferPositionToOffset(position)
  }

  override fun getBufferPosition(): BufferPosition = vimEditor.offsetToBufferPosition(offset)

  override fun getLine(): Int = getBufferPosition().line

  // Lazy, both of them: they read the global `injector` while constructing, so a caret built
  // before a host is installed - or by code that only wants offsets - would fail on a service it
  // never asked for. The headless host defers its own services for the same reason.
  override val markStorage: LocalMarkStorage by lazy { LocalMarkStorage(this) }

  /**
   * Per-caret registers, which is what multiple cursors need: each caret yanks into its own copy so
   * that `"ayiw` with three carets does not have them overwrite each other.
   */
  override val registerStorage: CaretRegisterStorage by lazy { CaretRegisterStorageBase(this) }

  /**
   * The column `j` and `k` return to, so passing through a short line does not lose the column.
   *
   * A remembered column is only meaningful until something else moves the caret: click somewhere
   * else, press `w`, and the column to come back to is wherever you now are. IntelliJ's caret
   * reports its own last column and IdeaVim only overrides it, but nothing here is keeping track,
   * so the position it was remembered at is stored alongside it and a read that finds the caret
   * somewhere else answers with the caret's actual column instead.
   *
   * Without this the field is whatever the last vertical motion left in it - which for a caret that
   * has never made one is zero, so the first `k` after a click went to the start of the line.
   */
  private var lastColumn: Int = 0
  private var lastColumnSetAt: Int = -1

  override var vimLastColumn: Int
    get() {
      if (offset != lastColumnSetAt) vimLastColumn = getBufferPosition().column
      return lastColumn
    }
    set(value) {
      lastColumn = value
      lastColumnSetAt = offset
    }

  override fun resetLastColumn() {
    vimLastColumn = getBufferPosition().column
  }

  // ---- Selection. VS Code has no separate idea of one: a selection there is a caret with its
  // anchor somewhere else, so this pair is what `VsCodeEditor.flushCarets` turns into an anchor.

  private var selectionStartOffset: Int = -1
  private var selectionEndOffset: Int = -1

  override val selectionStart: Int get() = selectionStartOffset
  override val selectionEnd: Int get() = selectionEndOffset

  override fun setSelection(start: Int, end: Int) {
    selectionStartOffset = start
    selectionEndOffset = end
  }

  override fun removeSelection() {
    selectionStartOffset = -1
    selectionEndOffset = -1
  }

  override fun hasSelection(): Boolean = selectionStartOffset >= 0 && selectionEndOffset >= 0

  override var vimSelectionStart: Int = 0

  override fun vimSelectionStartClear() {
    vimSelectionStart = offset
  }

  override val id: String = "vscode-caret-${nextId++}"

  /** A caret becomes invalid when its editor closes; the editor object goes with it. */
  override val isValid: Boolean get() = !vimEditor.isDisposed()

  // ---- Not reached yet. Each names itself if that changes.

  /**
   * A visual position is IntelliJ's idea: a line and column as *displayed*, after folds and inline
   * hints have shifted things about. This host has neither, so a visual position is a buffer
   * position wearing a different name and this is a plain move.
   *
   * The engine reaches for it while laying out a block selection, to put a caret on the right side
   * of a tab character - which is the one case where IntelliJ's columns and the buffer's disagree
   * even without folds. Here a tab is one column like anything else.
   */
  override fun moveToVisualPosition(position: VimVisualPosition) {
    offset = vimEditor.bufferPositionToOffset(BufferPosition(position.line, position.column))
  }
  /** Sets the remembered column and hands the caret back, since this one is never replaced. */
  override fun setVimLastColumnAndGetCaret(col: Int): VimCaret {
    vimLastColumn = col
    return this
  }
  /** Visual position is buffer position until folding and soft wrap are wired up. */
  override fun getVisualPosition(): VimVisualPosition {
    val position = getBufferPosition()
    return VimVisualPosition(position.line, position.column)
  }
  /**
   * Where this caret's insertion began, as a marker that moves with the text.
   *
   * `.` repeats an insertion by replaying what was typed, and `u` undoes one as a unit, so the
   * engine has to be able to ask afterwards how much of the buffer the session produced - which
   * only works if the start moved as characters went in ahead of it.
   */
  override var vimInsertStart: LiveRange = vimEditor.createLiveMarker(offset, offset)
  /**
   * The shape of the last visual operation - how many lines, how many columns, which kind - so that
   * `.` can repeat it on a different piece of text. Stored per caret because each one repeats its
   * own.
   */
  override var vimLastVisualOperatorRange: VisualChange? = null
  /** The caret's line, 1-based, because that is what Vimscript's `line(".")` means by a line. */
  override val vimLine: Int get() = getBufferPosition().line + 1

  /** Where the caret's line begins. A visual line is a buffer line while nothing folds. */
  override val visualLineStart: Int get() = vimEditor.getLineStartOffset(getBufferPosition().line)
  /**
   * The selection this caret last had, which is what `gv` restores. Per caret rather than per
   * editor, because multiple cursors each had their own.
   */
  override var lastSelectionInfo: SelectionInfo =
    SelectionInfo(null, null, SelectionType.CHARACTER_WISE)

  private companion object {
    /** Carets are compared by id, so two in the same buffer must not share one. */
    var nextId: Int = 0
  }
}
