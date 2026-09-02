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
  /**
   * Which caret the engine means when it says "the caret", and it moves between them.
   *
   * Laying out a blockwise Visual selection hands the flag to whichever caret sits on the block's
   * active end, because that is the one the engine moves next - see
   * [VsCodeEditor.vimSetSystemBlockSelectionSilently], which is where the flag is set.
   */
  override var isPrimary: Boolean,
) : VimCaret {

  /** Mutable, because editing and motion both move it, and this caret is never replaced. */
  override var offset: Int = offset
    private set

  override val editor: VimEditor get() = vimEditor

  override fun moveToOffsetNative(offset: Int) {
    this.offset = offset
    rememberColumn()
  }

  /**
   * IntelliJ's inlays are inline hints that occupy visual columns; VS Code's decorations do not
   * take part in offsets at all, so this is a plain move. The interface returns a caret because an
   * implementation may be immutable - this one moves itself and hands itself back.
   */
  override fun moveToInlayAwareOffset(newOffset: Int): VimCaret {
    offset = newOffset
    rememberColumn()
    return this
  }

  override fun moveToBufferPosition(position: BufferPosition) {
    offset = vimEditor.bufferPositionToOffset(position)
    rememberColumn()
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
   * Vim's `curswant`: the column `j` and `k` aim for, so that passing over a short line does not
   * lose it - and, in blockwise Visual, the column the block's edge is drawn at.
   *
   * This used to answer with the caret's *current* column whenever the caret had moved since the
   * column was last set, on the reasoning that a remembered column is only meaningful until
   * something else moves the caret. The reasoning is right and the rule is not: a vertical motion
   * sets the column and *then* moves the caret, so the very next read threw the answer away. On a
   * ragged file that is plainly wrong - `<C-V>` at column 2 and then `k` over a one-character line
   * dragged the whole block's left edge to column 1, and the next `k` to column 0, widening every
   * row of the block as it went.
   *
   * It is a plain field now, as IntelliJ's `lastColumnNumber` is. The engine resets it through
   * [resetLastColumn] when a horizontal motion or an edit makes it meaningless, and the one case
   * the engine cannot know about - the user moving the caret with the mouse - is handled where that
   * arrives, in [VsCodeEditor.syncCaretsFromEditor], which is what builds a caret from a click.
   */
  override var vimLastColumn: Int = 0

  /**
   * Keeps the remembered column in step with an ordinary move, which is IntelliJ's behaviour.
   *
   * IntelliJ's caret maintains `lastColumnNumber` itself on every move, and `vim-engine` is written
   * to that: `MotionActionHandler` sets the intended column around a motion, and the motions that do
   * not - `G`, and everything that returns an absolute offset - rely on the *editor* having kept it
   * current. With nothing keeping it here, `G` then `ll` left it at zero and the next `<C-V>k` drew
   * the block from column zero.
   *
   * Deliberately not called from [moveToVisualPosition], and deliberately restored by
   * [VsCodeEditor.vimSetSystemBlockSelectionSilently]: laying out a block moves every caret onto its
   * own line, and the engine reads the remembered column *after* that to decide where the block's
   * edge goes. A move made while rebuilding a block is not the user aiming at a column.
   */
  private fun rememberColumn() {
    vimLastColumn = getBufferPosition().column
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
   *
   * Made on demand rather than with the caret. Carets are rebuilt from VS Code's selections every
   * time one changes - a mouse click, a drag, a selection an extension made - and every marker in
   * the buffer is moved on every edit, so building one eagerly meant a mouse leaking work into
   * every keystroke that followed. Insert mode sets this before it reads it, so the collapsed
   * marker at the caret is only a starting value.
   */
  private var insertStart: LiveRange? = null

  override var vimInsertStart: LiveRange
    get() = insertStart ?: vimEditor.createLiveMarker(offset, offset).also { insertStart = it }
    set(value) {
      // The engine makes a fresh marker at the start of every insert session, and the one it
      // replaces is dropped here and nowhere else - so without this a caret leaves one behind for
      // every `i` the user has ever pressed, each of them still being moved on every edit.
      val previous = insertStart
      if (previous != null && previous !== value) vimEditor.buffer.removeMarker(previous)
      insertStart = value
    }
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
