/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.CaretRegisterStorage
import com.maddyhome.idea.vim.api.CaretRegisterStorageBase
import com.maddyhome.idea.vim.api.LocalMarkStorage
import com.maddyhome.idea.vim.api.SelectionInfo
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.group.visual.VisualChange
import com.maddyhome.idea.vim.group.visual.vimLeadSelectionOffset
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
   * Vim's `curswant`: the column `j` and `k` aim for, and the column a block's edge is drawn at.
   *
   * The rule is IdeaVim's, checked against it rather than invented: a remembered column is only
   * meaningful until something else moves the caret, so a read that finds the caret somewhere other
   * than where the column was set answers with the caret's own column. IntelliJ's version keys the
   * same check on a visual position; this one on an offset, which is the same thing where nothing
   * folds.
   *
   * What was actually wrong was not the rule but where the value lived. IdeaVim keeps it against
   * the *editor*, so every caret of a block shares one; here it is per caret, and a block's carets
   * are built fresh on every motion - so each new one started at zero and answered with whatever
   * column the line it landed on happened to clamp to. That is why `<C-V>` widened by a column on
   * every `j` over ragged text. [VsCodeEditor.vimSetSystemBlockSelectionSilently] seeds them all
   * from the caret the block is being drawn by.
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

  /**
   * Moves this caret so that it still points at the same text after an edit somewhere else.
   *
   * IntelliJ does not need this: a `Caret` is a document marker and the document moves it. VS Code's
   * selections are values the host writes, so with more than one caret every edit but the first
   * leaves the others describing a document that has changed underneath them - which is how `c` over
   * two `<C-n>` cursors ended in `'start' is out of bounds`.
   *
   * The rules are a document marker's. Anything before the edit is untouched; anything after it
   * moves by the difference in length; anything *inside* it collapses to where the replaced text
   * began, because the text it pointed at is gone. An offset exactly at [start] does not move: an
   * insertion there belongs after the caret, and the caret that asked for it is placed by its own
   * caller.
   */
  internal fun adjustForEdit(start: Int, end: Int, delta: Int) {
    offset = adjustOffset(offset, start, end, delta)
    if (hasSelection()) {
      selectionStartOffset = adjustOffset(selectionStartOffset, start, end, delta)
      selectionEndOffset = adjustOffset(selectionEndOffset, start, end, delta)
      if (selectionEndOffset <= selectionStartOffset) removeSelection()
    }
  }

  private fun adjustOffset(value: Int, start: Int, end: Int, delta: Int): Int = when {
    // Text inserted exactly where the caret is carries it, which is what an editor whose carets are
    // document markers does without being asked. `<C-T>` in Insert mode is the case: the engine
    // indents the line and moves nothing, because in IntelliJ the document moves the caret for it.
    start == end && value == start -> value + delta
    value <= start -> value
    value >= end -> value + delta
    else -> start
  }

  override fun removeSelection() {
    selectionStartOffset = -1
    selectionEndOffset = -1
  }

  override fun hasSelection(): Boolean = selectionStartOffset >= 0 && selectionEndOffset >= 0

  /**
   * Where the selection this caret is drawing started from.
   *
   * Unset rather than zero to begin with, and the difference is the whole of a bug. A block's
   * carets are built fresh on every motion and only the primary is handed the anchor, so every
   * other one answered `0` - harmless while the block is a block, because a block reads the anchor
   * off the primary, and wrong the moment it stops being one. `<C-V>khV` asked each caret for a
   * linewise selection from its own anchor, and the second caret drew one from the top of the file:
   * `selection expected [(41, 89)], actual [(0, 89)]`.
   *
   * IdeaVim's default is [vimLeadSelectionOffset] - the far end of whatever this caret already has
   * selected - and it is cached on first read exactly as it is here. [vimSelectionStartClear] puts
   * it back to unset rather than to the caret's offset, so that the next read recomputes it against
   * the selection as it is then.
   */
  override var vimSelectionStart: Int
    get() = anchor ?: vimLeadSelectionOffset.also { anchor = it }
    set(value) {
      anchor = value
    }

  private var anchor: Int? = null

  override fun vimSelectionStartClear() {
    anchor = null
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
   * `1v` and `.` can repeat it on a different piece of text.
   *
   * Per caret, because each one repeats its own, and *also* on the editor for whichever caret is
   * primary. That second half looks like belt and braces and is the only thing that makes `1v`
   * work twice. A block's carets are built fresh on every motion, so the caret that a block hands
   * the flag to is usually one that has never run an operation - `<C-V>jld1v` leaves the primary on
   * the block's second line, a caret invented moments earlier, and the `<ESC>kh1v` that follows
   * found nothing to rebuild the block from: `caret expected [16, 45], actual [15]`.
   *
   * IdeaVim gets this from `userDataCaretToEditor`, which mirrors the primary caret's value onto the
   * editor and reads it back when the caret's own is missing. It is how four pieces of per-caret
   * state survive a block being laid out, and CLAUDE.md's "the engine has no per-editor storage" is
   * about the engine rather than about the hosts - IdeaVim has this, and until now this host did
   * not.
   */
  override var vimLastVisualOperatorRange: VisualChange?
    get() = lastVisualOperatorRange ?: if (isTheCaret()) vimEditor.lastVisualOperatorRange else null
    set(value) {
      lastVisualOperatorRange = value
      if (isTheCaret()) vimEditor.lastVisualOperatorRange = value
    }

  private var lastVisualOperatorRange: VisualChange? = null

  /**
   * Whether the engine would call this "the caret", asked of the editor rather than of the flag.
   *
   * [isPrimary] is what the flag says; with no flag set anywhere the engine still answers with the
   * first caret, and IdeaVim's own check is `this == editor.caretModel.primaryCaret` for the same
   * reason.
   */
  private fun isTheCaret(): Boolean = vimEditor.primaryCaret() === this
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
