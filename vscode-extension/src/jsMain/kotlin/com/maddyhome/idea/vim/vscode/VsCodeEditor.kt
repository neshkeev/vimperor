/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.LineDeleteShift
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimCaretListener
import com.maddyhome.idea.vim.api.VimDocument
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimFoldRegion
import com.maddyhome.idea.vim.api.VimIndentConfig
import com.maddyhome.idea.vim.api.VimScrollingModel
import com.maddyhome.idea.vim.api.VimVirtualFile
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.VimEditorReplaceMask
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.SelectionType

/**
 * A [VimEditor] over a VS Code text editor.
 *
 * Reads go to [buffer] rather than to the document, because between a mutation and its flush the
 * document is behind - see [DocumentBuffer]. Everything in here is therefore arithmetic over one
 * string, which is also why it can be tested without a VS Code window.
 *
 * Only what running a command actually reaches is implemented. The rest throws with its own name,
 * the way the headless host was built: the set of editor operations a Vim command needs is not
 * knowable by reading the code, and a member that quietly returns zero is a bug that surfaces
 * somewhere else.
 */
class VsCodeEditor(val nativeEditor: TextEditor) : MutableVimEditor {

  val buffer: DocumentBuffer = DocumentBuffer(nativeEditor)

  private val vimCarets: MutableList<VsCodeCaret> = mutableListOf()

  init {
    syncCaretsFromEditor()
  }

  /** The text as the engine sees it: its own edits included, whether or not VS Code has them yet. */
  val text: String get() = buffer.text

  // ---- Buffer identity.
  //
  // Marks depend on this and fail silently without it. `VimMarkServiceBase.createMark` returns null
  // outright when `getVirtualFile()` is null, and `getLocalMark` returns null when the editor has no
  // path - so a buffer with no identity has no marks at all, not `'[`, not `']`, not one lowercase
  // mark, and with no error to say why. A URI is what VS Code has, and every buffer has one:
  // untitled documents included, which is why this uses the URI rather than a file-system path.

  private val identity: String = "${nativeEditor.document.uri.scheme}://${nativeEditor.document.uri.path}"

  override fun getPath(): String = identity

  override fun extractProtocol(): String? = nativeEditor.document.uri.scheme

  override fun getVirtualFile(): VimVirtualFile = object : VimVirtualFile {
    override val path: String = identity
    override val protocol: String = nativeEditor.document.uri.scheme
    override val extension: String? = identity.substringAfterLast('.', "").ifEmpty { null }
  }

  /** IntelliJ keys the jump list per project window; VS Code's equivalent is the window itself. */
  override val projectId: String get() = "vscode"

  // ---- Text and offsets.

  /**
   * Line start offsets over [buffer], with a final entry at the end, rebuilt when the buffer moves.
   *
   * Not VS Code's own `positionAt`, which answers about the *document* - and the document is the
   * version the engine is not looking at until a flush.
   */
  private var indexedRevision: Int = -1
  private var lineStarts: IntArray = intArrayOf(0)

  private fun starts(): IntArray {
    if (indexedRevision != buffer.revision) {
      lineStarts = buildList {
        add(0)
        buffer.text.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
      }.toIntArray()
      indexedRevision = buffer.revision
    }
    return lineStarts
  }

  override fun text(): CharSequence = buffer.text

  override fun fileSize(): Long = buffer.text.length.toLong()

  /** A trailing newline does not open a line, which is how both Vim and VS Code count them. */
  override fun nativeLineCount(): Int {
    val text = buffer.text
    return if (text.isEmpty()) 0 else starts().size - (if (text.endsWith("\n")) 1 else 0)
  }

  override fun getLineStartOffset(line: Int): Int {
    val starts = starts()
    return when {
      line < 0 -> 0
      line >= starts.size -> buffer.text.length
      else -> starts[line]
    }
  }

  override fun getLineEndOffset(line: Int): Int {
    val starts = starts()
    return when {
      line < 0 -> 0
      line + 1 >= starts.size -> buffer.text.length
      // The offset of the newline itself, not of the next line.
      else -> starts[line + 1] - 1
    }
  }

  override fun offsetToBufferPosition(offset: Int): BufferPosition {
    if (offset < 0 || offset > buffer.text.length) return BufferPosition(-1, -1)
    val starts = starts()
    var line = starts.indexOfFirst { it > offset }
    line = if (line < 0) starts.size - 1 else line - 1
    return BufferPosition(line, offset - starts[line])
  }

  override fun bufferPositionToOffset(position: BufferPosition): Int {
    val starts = starts()
    val line = position.line.coerceIn(0, starts.size - 1)
    return (starts[line] + position.column).coerceIn(0, buffer.text.length)
  }

  /**
   * Visual position is buffer position.
   *
   * They differ once lines are folded or soft-wrapped, and VS Code does both. Nothing that folds or
   * wraps is implemented yet, so saying they are equal is true of the editor as it stands and false
   * of the one it will become - the screen-shaped members below are the ones that will need it.
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

  // ---- Mutation. All of it lands in the buffer synchronously; VS Code is caught up by `flush`.

  override fun insertText(caret: VimCaret, atPosition: Int, text: CharSequence) {
    buffer.insert(atPosition, text.toString())
  }

  override fun replaceString(start: Int, end: Int, newString: String) {
    buffer.replace(start, end, newString)
  }

  override fun deleteString(range: TextRange) {
    buffer.replace(range.startOffset, range.endOffset, "")
  }

  /** `o` and `O`. Vim's `addLine` opens a line before [atPosition] and answers where it starts. */
  override fun addLine(atPosition: Int): Int {
    val insertAt = getLineStartOffset(atPosition)
    buffer.insert(insertAt, "\n")
    return insertAt
  }

  /**
   * Writes everything the engine did - the text, then where the carets ended up - to VS Code.
   *
   * The order matters: a selection set against the pre-edit document would land in the wrong place,
   * so carets follow the text rather than accompanying it.
   */
  fun flush(onResult: (Boolean) -> Unit = {}) {
    buffer.flush { applied ->
      if (applied) flushCarets() else syncCaretsFromEditor()
      onResult(applied)
    }
  }

  // ---- Carets. VS Code calls them selections; a collapsed selection is a plain caret.

  private fun syncCaretsFromEditor() {
    val selections = nativeEditor.selections
    vimCarets.clear()
    selections.forEachIndexed { index, selection ->
      val offset = nativeEditor.document.offsetAt(selection.active)
      vimCarets += VsCodeCaret(this, offset, isPrimary = index == 0)
    }
    if (vimCarets.isEmpty()) vimCarets += VsCodeCaret(this, 0, isPrimary = true)
  }

  private fun flushCarets() {
    val document = nativeEditor.document
    val selections = vimCarets.map { caret ->
      val position = document.positionAt(caret.offset)
      FlushedSelection(position, position)
    }
    nativeEditor.selections = selections.toTypedArray()
    selections.firstOrNull()?.let { nativeEditor.selection = it }
  }

  /**
   * Collapses every selection to its caret. VS Code hears about it at the next flush, along with
   * everything else - a selection pushed now would be positioned against the pre-edit document.
   */
  override fun removeSelection() {
    vimCarets.forEach { it.removeSelection() }
  }

  override fun carets(): List<VimCaret> = vimCarets

  override fun nativeCarets(): List<VimCaret> = vimCarets

  override fun currentCaret(): VimCaret = vimCarets.first()

  override fun primaryCaret(): VimCaret = vimCarets.first()

  private var inForEachCaret = false

  override fun forEachCaret(action: (VimCaret) -> Unit) {
    inForEachCaret = true
    try {
      vimCarets.toList().forEach(action)
    } finally {
      inForEachCaret = false
    }
  }

  override fun forEachNativeCaret(action: (VimCaret) -> Unit, reverse: Boolean) {
    inForEachCaret = true
    try {
      val carets = if (reverse) vimCarets.reversed() else vimCarets.toList()
      carets.forEach(action)
    } finally {
      inForEachCaret = false
    }
  }

  /** IntelliJ forbids nesting per-caret walks and the engine asks before starting one. */
  override fun isInForEachCaretScope(): Boolean = inForEachCaret

  /** IntelliJ replaces caret objects as the document changes; these are mutable and never replaced. */
  override fun <T : ImmutableVimCaret> findLastVersionOfCaret(caret: T): T = caret

  // ---- Editor state the engine keeps here because it is per-window.

  override var mode: Mode = Mode.NORMAL()
  override var isReplaceCharacter: Boolean = false
  override var vimChangeActionSwitchMode: Mode? = null
  override var insertMode: Boolean = false

  override fun isWritable(): Boolean = true
  override fun isDocumentWritable(): Boolean = true
  override fun isDisposed(): Boolean = false

  /** IntelliJ's one-line editors are inline input fields; VS Code has no such thing. */
  override fun isOneLineMode(): Boolean = false

  /** IntelliJ's live templates. VS Code's snippets are its own feature and are not wired up. */
  override fun isTemplateActive(): Boolean = false

  override fun hasUnsavedChanges(): Boolean = nativeEditor.document.isUntitled

  /** IntelliJ's guarded blocks; VS Code has no equivalent, so there is nothing to check. */
  override fun startGuardedBlockChecking() {}
  override fun stopGuardedBlockChecking() {}

  /**
   * The range, unchanged. The engine's own comment calls this "a function for refactoring, get rid
   * of it": it lets a host widen a delete to swallow a trailing newline, and plain text has nothing
   * to adjust.
   */
  override fun search(
    pair: Pair<Int, Int>,
    editor: VimEditor,
    shiftType: LineDeleteShift,
  ): Pair<Pair<Int, Int>, LineDeleteShift> = pair to shiftType

  // ---- Folding. VS Code folds, and none of it is wired up yet; "nothing is folded" is the
  // honest answer for an editor that does not yet ask VS Code about it.

  override fun getCollapsedFoldRegionAtOffset(offset: Int): VimFoldRegion? = null
  override fun getFoldRegionsAtOffset(offset: Int): List<VimFoldRegion> = emptyList()
  override fun getCollapsedFoldRegionAtVisualStartLine(line: Int): VimFoldRegion? = null
  override fun getAllFoldRegions(): List<VimFoldRegion> = emptyList()

  // ---- Not reached yet. Each names itself if that changes.

  override fun getLineRange(line: Int): Pair<Int, Int> = TODO("VsCodeEditor.getLineRange")
  override fun getScrollingModel(): VimScrollingModel = TODO("VsCodeEditor.getScrollingModel")
  override fun removeCaret(caret: VimCaret): Unit = TODO("VsCodeEditor.removeCaret")
  override fun addCaret(offset: Int): VimCaret? = TODO("VsCodeEditor.addCaret")
  override fun removeSecondaryCarets(): Unit = TODO("VsCodeEditor.removeSecondaryCarets")
  override fun vimSetSystemBlockSelectionSilently(start: BufferPosition, end: BufferPosition): Unit =
    TODO("VsCodeEditor.vimSetSystemBlockSelectionSilently")
  override fun addCaretListener(listener: VimCaretListener): Unit = TODO("VsCodeEditor.addCaretListener")
  override fun removeCaretListener(listener: VimCaretListener): Unit = TODO("VsCodeEditor.removeCaretListener")
  override fun exitInsertMode(context: ExecutionContext): Unit = TODO("VsCodeEditor.exitInsertMode")
  override fun exitSelectModeNative(adjustCaret: Boolean): Unit = TODO("VsCodeEditor.exitSelectModeNative")
  override fun getLastVisualLineColumnNumber(line: Int): Int = TODO("VsCodeEditor.getLastVisualLineColumnNumber")
  override fun createLiveMarker(start: Int, end: Int): LiveRange = TODO("VsCodeEditor.createLiveMarker")
  override fun createIndentBySize(size: Int): String = TODO("VsCodeEditor.createIndentBySize")
  override fun getFoldRegionAtLine(line: Int): VimFoldRegion? = TODO("VsCodeEditor.getFoldRegionAtLine")
  override fun applyFoldLevel(foldLevel: Int): Unit = TODO("VsCodeEditor.applyFoldLevel")
  override fun getMaxFoldDepth(): Int = TODO("VsCodeEditor.getMaxFoldDepth")
  override fun createFoldRegion(startOffset: Int, endOffset: Int, collapse: Boolean): VimFoldRegion? =
    TODO("VsCodeEditor.createFoldRegion")
  override fun deleteFoldRegionAtOffset(offset: Int): Boolean = TODO("VsCodeEditor.deleteFoldRegionAtOffset")
  override fun deleteFoldRegionsRecursivelyAtOffset(offset: Int): Boolean =
    TODO("VsCodeEditor.deleteFoldRegionsRecursivelyAtOffset")
  override val lfMakesNewLine: Boolean get() = TODO("VsCodeEditor.lfMakesNewLine")
  override val indentConfig: VimIndentConfig get() = TODO("VsCodeEditor.indentConfig")
  override var replaceMask: VimEditorReplaceMask?
    get() = TODO("VsCodeEditor.replaceMask")
    set(_) = TODO("VsCodeEditor.replaceMask")
  override var vimLastSelectionType: SelectionType?
    get() = TODO("VsCodeEditor.vimLastSelectionType")
    set(_) = TODO("VsCodeEditor.vimLastSelectionType")
  override val document: VimDocument get() = TODO("VsCodeEditor.document")
}

/** A collapsed selection to hand back to VS Code, which has no separate notion of a caret. */
private class FlushedSelection(
  override val anchor: Position,
  override val active: Position,
) : Selection
