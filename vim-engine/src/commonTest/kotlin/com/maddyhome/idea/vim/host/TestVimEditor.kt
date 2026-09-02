/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimEditorBase
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.api.LineDeleteShift
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.api.VimVirtualFile
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.api.VimScrollingModel
import com.maddyhome.idea.vim.api.VimCaretListener
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.api.VimFoldRegion
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.api.VimIndentConfig
import com.maddyhome.idea.vim.common.VimEditorReplaceMask
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.api.VimDocument

/**
 * A real, in-memory [VimEditor] over a fixed string, for the regex tests.
 *
 * These tests used Mockito, which kept them on the JVM and left the regex *matcher* - the largest
 * untested surface phase 0 identified - with no evidence on JS at all. A hand-written fake runs
 * anywhere, and is stricter besides: an unstubbed member throws here, where Mockito quietly returns
 * null or zero.
 *
 * Only what the regex engine actually asks for is implemented. Everything else is [TODO], so a test
 * that starts depending on more of the editor says which member it needs instead of matching against
 * a silent default.
 */
class TestVimEditor(text: String, private val carets: List<VimCaret>) : VimEditorBase(), MutableVimEditor {

  /**
   * The buffer. Mutable because editing changes it, and rebuilt line starts with it - the offsets
   * are what turns an offset into a line and column, so they cannot go stale.
   */
  var text: String = text
    private set

  /** Line start offsets, plus a final entry at the end of the text. */
  private var lineStarts: IntArray = lineStartsOf(text)

  init {
    // So the editor group can list it. Anything that walks the open editors - the mark service on
    // every edit, for one - sees nothing at all otherwise.
    (injector.editorGroup as? HeadlessEditorGroup)?.register(this)
  }

  private fun lineStartsOf(text: String): IntArray = buildList {
    add(0)
    text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
  }.toIntArray()

  private fun setText(newText: String) {
    text = newText
    lineStarts = lineStartsOf(newText)
  }

  override fun insertText(caret: VimCaret, atPosition: Int, text: CharSequence) {
    val at = atPosition.coerceIn(0, this.text.length)
    setText(this.text.substring(0, at) + text + this.text.substring(at))
  }

  override fun replaceString(start: Int, end: Int, newString: String) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    setText(text.substring(0, from) + newString + text.substring(to))
  }

  /**
   * Appends a line, returning the offset of its start. Vim's `addLine` is used for `o` and `O`; a
   * host with a real document would insert into it, and here the buffer is the document.
   */
  override fun addLine(atPosition: Int): Int {
    val insertAt = getLineStartOffset(atPosition)
    setText(text.substring(0, insertAt) + "\n" + text.substring(insertAt))
    return insertAt
  }

  override fun text(): CharSequence = text

  override fun fileSize(): Long = text.length.toLong()

  // A trailing newline does not open a line, matching how the editor counts them.
  override fun nativeLineCount(): Int =
    if (text.isEmpty()) 0 else lineStarts.size - (if (text.endsWith("\n")) 1 else 0)

  override fun getLineStartOffset(line: Int): Int = when {
    line < 0 -> 0
    line >= lineStarts.size -> text.length
    else -> lineStarts[line]
  }

  override fun getLineEndOffset(line: Int): Int = when {
    line < 0 -> 0
    line + 1 >= lineStarts.size -> text.length
    // The offset of the newline itself, not of the next line.
    else -> lineStarts[line + 1] - 1
  }

  override fun offsetToBufferPosition(offset: Int): BufferPosition {
    if (offset < 0) return BufferPosition(-1, -1)
    if (offset > text.length) return BufferPosition(-1, -1)
    var line = lineStarts.indexOfFirst { it > offset }
    line = if (line < 0) lineStarts.size - 1 else line - 1
    return BufferPosition(line, offset - lineStarts[line])
  }

  override fun bufferPositionToOffset(position: BufferPosition): Int {
    val line = position.line.coerceIn(0, lineStarts.size - 1)
    return (lineStarts[line] + position.column).coerceIn(0, text.length)
  }

  override fun carets(): List<VimCaret> = carets

  override fun nativeCarets(): List<VimCaret> = carets

  override fun currentCaret(): VimCaret = carets.first()

  override fun primaryCaret(): VimCaret = carets.first()

  // ---- Not reached by the regex engine. Each names itself if that ever changes.

  override fun getLineRange(line: Int): Pair<Int, Int> = TODO("TestVimEditor.getLineRange is not implemented yet")
  override fun forEachCaret(action: (VimCaret) -> Unit) {
    inForEachCaret = true
    try {
      carets.forEach(action)
    } finally {
      inForEachCaret = false
    }
  }

  override fun forEachNativeCaret(action: (VimCaret) -> Unit, reverse: Boolean) =
    forEachCaret(action)

  /**
   * Whether a per-caret walk is in progress. IntelliJ forbids nesting them, and the engine asks
   * before starting one; with a single caret there is nothing to nest.
   */
  override fun isInForEachCaretScope(): Boolean = inForEachCaret

  private var inForEachCaret = false

  /** Writable. A read-only buffer is a real editor state, but not one these tests exercise. */
  override fun isWritable(): Boolean = true

  override fun isDocumentWritable(): Boolean = true

  /** False - a one-line editor is IntelliJ's inline input field, not a buffer. */
  override fun isOneLineMode(): Boolean = false

  /**
   * The range, unchanged. The engine's own comment calls this "a function for refactoring, get rid
   * of it": it exists so a host can widen a delete to swallow a trailing newline. A buffer that is
   * plain text has nothing to adjust, so it hands back what it was given.
   */
  override fun search(
    pair: Pair<Int, Int>,
    editor: VimEditor,
    shiftType: LineDeleteShift,
  ): Pair<Pair<Int, Int>, LineDeleteShift> = pair to shiftType

  /**
   * Visual position equals buffer position here. They differ in a real editor when lines are folded
   * or soft-wrapped, and nothing is displayed to fold or wrap.
   */
  override fun offsetToVisualPosition(offset: Int): VimVisualPosition {
    val position = offsetToBufferPosition(offset)
    return VimVisualPosition(position.line, position.column)
  }

  override fun visualPositionToOffset(position: VimVisualPosition): Int =
    bufferPositionToOffset(BufferPosition(position.line, position.column))

  override fun visualPositionToBufferPosition(position: VimVisualPosition): BufferPosition =
    BufferPosition(position.line, position.column)

  override fun bufferPositionToVisualPosition(position: BufferPosition): VimVisualPosition =
    VimVisualPosition(position.line, position.column)

  /**
   * A file, because marks require one.
   *
   * `VimMarkServiceBase.createMark` returns null outright when `getVirtualFile()` is null, so a
   * buffer with no file has no marks at all - not `'[`, not `']`, not a single lowercase mark, and
   * silently rather than with an error. Its `path` has to agree with [getPath], since marks are
   * stored under the file's path and read back under the editor's.
   *
   * This is the constraint a VS Code host meets first: whatever it uses for buffer identity has to
   * arrive here, and an untitled buffer with no URI would lose marks.
   */
  override fun getVirtualFile(): VimVirtualFile = TestVirtualFile
  override fun deleteString(range: TextRange) {
    replaceString(range.startOffset, range.endOffset, "")
  }

  override fun getScrollingModel(): VimScrollingModel = TODO("TestVimEditor.getScrollingModel is not implemented yet")
  override fun removeCaret(caret: VimCaret): Unit = TODO("TestVimEditor.removeCaret is not implemented yet")
  override fun addCaret(offset: Int): VimCaret? = TODO("TestVimEditor.addCaret is not implemented yet")
  override fun removeSecondaryCarets(): Unit = TODO("TestVimEditor.removeSecondaryCarets is not implemented yet")
  override fun vimSetSystemBlockSelectionSilently(start: BufferPosition, end: BufferPosition): Unit = TODO("TestVimEditor.vimSetSystemBlockSelectionSilently is not implemented yet")
  override fun addCaretListener(listener: VimCaretListener): Unit = TODO("TestVimEditor.addCaretListener is not implemented yet")
  override fun removeCaretListener(listener: VimCaretListener): Unit = TODO("TestVimEditor.removeCaretListener is not implemented yet")
  override fun isDisposed(): Boolean = false

  override fun removeSelection(): Unit = TODO("TestVimEditor.removeSelection is not implemented yet")
  /**
   * A stable name, because local marks are keyed by it.
   *
   * `VimMarkServiceBase.getLocalMark` returns null outright when the editor has no path, so a
   * buffer without one silently has no marks - `'[`, `']` and every lowercase mark included. That
   * is real engine behaviour rather than a gap in this fake, and it is worth knowing before a host
   * hands the engine a scratch buffer: whatever a VS Code host uses for identity, a URI most
   * likely, has to arrive here.
   */
  override fun getPath(): String = "headless://buffer"
  override fun extractProtocol(): String? = TODO("TestVimEditor.extractProtocol is not implemented yet")
  override fun exitInsertMode(context: ExecutionContext): Unit = TODO("TestVimEditor.exitInsertMode is not implemented yet")
  override fun exitSelectModeNative(adjustCaret: Boolean): Unit = TODO("TestVimEditor.exitSelectModeNative is not implemented yet")
  /** No live templates without an IDE that has them. */
  override fun isTemplateActive(): Boolean = false

  override fun startGuardedBlockChecking() {}

  override fun stopGuardedBlockChecking() {}

  override fun hasUnsavedChanges(): Boolean = false

  override fun getLastVisualLineColumnNumber(line: Int): Int = TODO("TestVimEditor.getLastVisualLineColumnNumber is not implemented yet")
  override fun createLiveMarker(start: Int, end: Int): LiveRange = TODO("TestVimEditor.createLiveMarker is not implemented yet")
  override fun createIndentBySize(size: Int): String = TODO("TestVimEditor.createIndentBySize is not implemented yet")
  override fun getCollapsedFoldRegionAtOffset(offset: Int): VimFoldRegion? = null

  override fun getFoldRegionsAtOffset(offset: Int): List<VimFoldRegion> = emptyList()

  override fun getFoldRegionAtLine(line: Int): VimFoldRegion? = TODO("TestVimEditor.getFoldRegionAtLine is not implemented yet")
  /** Nothing is folded, because nothing is displayed. */
  override fun getCollapsedFoldRegionAtVisualStartLine(line: Int): VimFoldRegion? = null

  override fun getAllFoldRegions(): List<VimFoldRegion> = emptyList()

  override fun applyFoldLevel(foldLevel: Int): Unit = TODO("TestVimEditor.applyFoldLevel is not implemented yet")
  override fun getMaxFoldDepth(): Int = TODO("TestVimEditor.getMaxFoldDepth is not implemented yet")
  override fun createFoldRegion(startOffset: Int, endOffset: Int, collapse: Boolean): VimFoldRegion? = TODO("TestVimEditor.createFoldRegion is not implemented yet")
  override fun deleteFoldRegionAtOffset(offset: Int): Boolean = TODO("TestVimEditor.deleteFoldRegionAtOffset is not implemented yet")
  override fun deleteFoldRegionsRecursivelyAtOffset(offset: Int): Boolean = TODO("TestVimEditor.deleteFoldRegionsRecursivelyAtOffset is not implemented yet")
  /**
   * The caret itself. IntelliJ replaces caret objects as the document changes, so the engine asks
   * for the current version of one it is holding; this caret is mutable and never replaced.
   */
  override fun <T : ImmutableVimCaret> findLastVersionOfCaret(caret: T): T = caret

  /**
   * The mode lives in the state machine, and `VimEditorBase` is what reads and writes it there.
   *
   * An editor that kept the mode in a field of its own would set it successfully and change
   * nothing: `KeyHandler` asks the state machine, so `i` would appear to work and the next key
   * would still be read as a normal-mode command.
   */
  override fun updateMode(mode: Mode) {
    (injector.vimState as VimStateMachineImpl).mode = mode
  }

  override fun updateIsReplaceCharacter(isReplaceCharacter: Boolean) {
    (injector.vimState as VimStateMachineImpl).isReplaceCharacter = isReplaceCharacter
  }

  override val lfMakesNewLine: Boolean get() = TODO("TestVimEditor.lfMakesNewLine is not implemented yet")
  /** Where to go after a change finishes - insert after `cw`, normal after `x`. */
  override var vimChangeActionSwitchMode: Mode? = null

  override val indentConfig: VimIndentConfig get() = TODO("TestVimEditor.indentConfig is not implemented yet")
  override var replaceMask: VimEditorReplaceMask?
    get() = TODO("TestVimEditor.replaceMask is not implemented yet")
    set(_) = TODO("TestVimEditor.replaceMask is not implemented yet")
  /**
   * One project, one id. The jump list is keyed by it, because IntelliJ keeps a separate jump list
   * per project window.
   */
  override val projectId: String get() = "headless"

  override var vimLastSelectionType: SelectionType?
    get() = TODO("TestVimEditor.vimLastSelectionType is not implemented yet")
    set(_) = TODO("TestVimEditor.vimLastSelectionType is not implemented yet")
  override var insertMode: Boolean = false

  override val document: VimDocument get() = TODO("TestVimEditor.document is not implemented yet")
}

/** The one buffer a headless test has, named so that marks can be keyed by it. */
internal object TestVirtualFile : VimVirtualFile {
  override val path: String = "headless://buffer"
  override val protocol: String = "headless"
  override val extension: String? = null
}
