/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.regexp

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.LocalMarkStorage
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.api.SelectionInfo
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.group.visual.VisualChange
import com.maddyhome.idea.vim.api.CaretRegisterStorage
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

  override fun resetLastColumn(): Unit = TODO("TestVimCaret.resetLastColumn is not needed by the regex tests")
  override fun vimSelectionStartClear(): Unit = TODO("TestVimCaret.vimSelectionStartClear is not needed by the regex tests")
  override fun setSelection(start: Int, end: Int): Unit = TODO("TestVimCaret.setSelection is not needed by the regex tests")
  override fun removeSelection(): Unit = TODO("TestVimCaret.removeSelection is not needed by the regex tests")
  override fun moveToVisualPosition(position: VimVisualPosition): Unit = TODO("TestVimCaret.moveToVisualPosition is not needed by the regex tests")
  override fun setVimLastColumnAndGetCaret(col: Int): VimCaret = TODO("TestVimCaret.setVimLastColumnAndGetCaret is not needed by the regex tests")
  override var vimLastColumn: Int
    get() = TODO("TestVimCaret.vimLastColumn is not needed by the regex tests")
    set(_) = TODO("TestVimCaret.vimLastColumn is not needed by the regex tests")
  override var vimSelectionStart: Int
    get() = TODO("TestVimCaret.vimSelectionStart is not needed by the regex tests")
    set(_) = TODO("TestVimCaret.vimSelectionStart is not needed by the regex tests")
  override var vimInsertStart: LiveRange
    get() = TODO("TestVimCaret.vimInsertStart is not needed by the regex tests")
    set(_) = TODO("TestVimCaret.vimInsertStart is not needed by the regex tests")
  override var vimLastVisualOperatorRange: VisualChange?
    get() = TODO("TestVimCaret.vimLastVisualOperatorRange is not needed by the regex tests")
    set(_) = TODO("TestVimCaret.vimLastVisualOperatorRange is not needed by the regex tests")
  override fun getBufferPosition(): BufferPosition = TODO("TestVimCaret.getBufferPosition is not needed by the regex tests")
  override fun getVisualPosition(): VimVisualPosition = TODO("TestVimCaret.getVisualPosition is not needed by the regex tests")
  override fun getLine(): Int = TODO("TestVimCaret.getLine is not needed by the regex tests")
  override fun hasSelection(): Boolean = TODO("TestVimCaret.hasSelection is not needed by the regex tests")
  override val id: String get() = TODO("TestVimCaret.id is not needed by the regex tests")
  override val isValid: Boolean get() = TODO("TestVimCaret.isValid is not needed by the regex tests")
  override val vimLine: Int get() = TODO("TestVimCaret.vimLine is not needed by the regex tests")
  override val visualLineStart: Int get() = TODO("TestVimCaret.visualLineStart is not needed by the regex tests")
  override var lastSelectionInfo: SelectionInfo
    get() = TODO("TestVimCaret.lastSelectionInfo is not needed by the regex tests")
    set(_) = TODO("TestVimCaret.lastSelectionInfo is not needed by the regex tests")
  override val registerStorage: CaretRegisterStorage get() = TODO("TestVimCaret.registerStorage is not needed by the regex tests")
}
