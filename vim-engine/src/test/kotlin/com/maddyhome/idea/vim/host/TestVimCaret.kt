/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.LocalMarkStorage
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.api.SelectionInfo
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.group.visual.VisualChange
import com.maddyhome.idea.vim.api.CaretRegisterStorage
import com.maddyhome.idea.vim.api.CaretRegisterStorageBase
import com.maddyhome.idea.vim.mark.Mark
import com.maddyhome.idea.vim.mark.VimMark

/**
 * The caret the regex engine reads: an offset, a selection, and the local marks that `\%'m`,
 * `\%<'m` and `\%>'m` compare against. See [TestVimEditor] for why these are hand-written rather
 * than mocked.
 */
class TestVimCaret(
  offset: Int,
  override val selectionStart: Int = -1,
  override val selectionEnd: Int = -1,
  marks: Map<Char, BufferPosition> = emptyMap(),
  isPrimary: Boolean = false,
) : VimCaret {

  /**
   * Mutable, because editing moves it. The interface returns a caret from `moveToInlayAwareOffset`
   * "because the caret implementation may be immutable" - this one is not, so it moves itself and
   * returns itself, which is what an editor with one caret per buffer does.
   */
  override var offset: Int = offset
    private set

  override fun moveToOffsetNative(offset: Int) {
    this.offset = offset
  }

  override fun moveToInlayAwareOffset(newOffset: Int): VimCaret {
    offset = newOffset
    return this
  }

  override fun moveToBufferPosition(position: BufferPosition) {
    offset = editorRef?.bufferPositionToOffset(position) ?: position.column
  }

  /**
   * Set once the editor exists. The caret and its editor point at each other - the caret needs the
   * editor to turn a buffer position into an offset, and the mark service asks a caret which editor
   * it belongs to - so one of the two has to be filled in after construction.
   */
  var editorRef: TestVimEditor? = null

  override val editor: VimEditor
    get() = editorRef ?: error("TestVimCaret was used before its editor was set")

  /**
   * Defaults to false, which is what the regex tests need: they build a caret with no injector
   * installed, and `LocalMarkStorage.getMark` asks `injector.markService` for a primary caret while
   * reading its own map otherwise.
   *
   * A test with a host installed should pass true, because a lone caret in a buffer *is* the
   * primary one - and the change marks `'[` and `']` are stored by the mark service rather than on
   * the caret, so they are only readable when the caret admits to being primary.
   */
  override val isPrimary: Boolean = isPrimary

  override val markStorage: LocalMarkStorage = LocalMarkStorage(this).also { storage ->
    marks.forEach { (char, position) ->
      storage.setMark(VimMark(char, position.line, position.column, "", ""))
    }
  }

  // ---- Not reached by the regex engine. Each names itself if that ever changes.

  override fun resetLastColumn() {}

  override fun vimSelectionStartClear() {}

  override fun setSelection(start: Int, end: Int) {}

  /**
   * Nothing to remove. The selection is fixed at construction here, because the regex tests express
   * one by writing tags into the buffer text rather than by moving a caret.
   */
  override fun removeSelection() {}

  override fun moveToVisualPosition(position: VimVisualPosition): Unit = TODO("TestVimCaret.moveToVisualPosition is not implemented yet")
  /**
   * The remembered column, which `j` and `k` walk down from and every line-wise edit resets.
   *
   * A `TODO` until `:delete` reached it: the deletion happened and then this threw, so the buffer
   * changed and the command reported a failure. Nothing here replaces the caret, so the caret this
   * returns is this one.
   */
  override fun setVimLastColumnAndGetCaret(col: Int): VimCaret {
    vimLastColumn = col
    return this
  }
  /**
   * The column `j` and `k` try to return to. Vim remembers it across vertical motions so that
   * moving through a short line and out the other side lands back where you started.
   */
  override var vimLastColumn: Int = 0

  override var vimSelectionStart: Int = 0

  override var vimInsertStart: LiveRange
    get() = TODO("TestVimCaret.vimInsertStart is not implemented yet")
    set(_) = TODO("TestVimCaret.vimInsertStart is not implemented yet")
  override var vimLastVisualOperatorRange: VisualChange?
    get() = TODO("TestVimCaret.vimLastVisualOperatorRange is not implemented yet")
    set(_) = TODO("TestVimCaret.vimLastVisualOperatorRange is not implemented yet")
  /** The caret's offset as a line and column, which only the editor can work out. */
  override fun getBufferPosition(): BufferPosition = editor.offsetToBufferPosition(offset)

  override fun getVisualPosition(): VimVisualPosition = TODO("TestVimCaret.getVisualPosition is not implemented yet")
  override fun getLine(): Int = getBufferPosition().line

  /** A selection exists when the two ends were given, which is how the regex tests build one. */
  override fun hasSelection(): Boolean = selectionStart >= 0 && selectionEnd >= 0
  override val id: String get() = "headless-caret"

  /** Always. A caret becomes invalid when its editor closes, and this one never closes. */
  override val isValid: Boolean get() = true

  /**
   * The line the caret is on, counting from one, which is what Vimscript counts in.
   *
   * A `TODO` until `line('.')` reached it - and `line()` is the function half the position family
   * is built on, so `getpos()`, `cursor()` and `setline()` were all reaching it at once.
   */
  override val vimLine: Int get() = getBufferPosition().line + 1
  override val visualLineStart: Int get() = TODO("TestVimCaret.visualLineStart is not implemented yet")
  /**
   * The `'<` and `'>` marks, which every caret has whether or not it has ever been in Visual.
   *
   * Two ends that were never set, to begin with - which is what the mark service reads it as when
   * it walks the marks of a file. A `TODO` here made every such walk throw, and the mark service
   * walks on any edit.
   */
  override var lastSelectionInfo: SelectionInfo = SelectionInfo(null, null, SelectionType.CHARACTER_WISE)
  /**
   * Per-caret registers, which is what multiple cursors need: each caret yanks into its own copy so
   * that `"ayiw` on three carets does not have them overwrite each other.
   */
  override val registerStorage: CaretRegisterStorage = CaretRegisterStorageBase(this)

}
