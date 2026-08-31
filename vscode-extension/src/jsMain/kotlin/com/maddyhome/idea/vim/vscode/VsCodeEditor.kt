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
import com.maddyhome.idea.vim.api.VimEditorBase
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.api.VimFoldRegion
import com.maddyhome.idea.vim.api.VimIndentConfig
import com.maddyhome.idea.vim.api.VimScrollingModel
import com.maddyhome.idea.vim.api.VimVirtualFile
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.common.ChangesListener
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
class VsCodeEditor(val nativeEditor: TextEditor) : VimEditorBase(), MutableVimEditor {

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

  /**
   * A line and a column, as an offset.
   *
   * The column is clamped to the line, which is the whole of the difference between this and adding
   * the column to the line's start: a column past the end of a short line has to come back as that
   * line's end, not as a position on the line below. `j` and `k` are built on exactly that - they
   * ask for the column they are aiming for on the next line and let the answer be shorter - so
   * without the clamp a `j` from column 3 onto a one-character line silently skipped it and landed
   * on the line after.
   */
  override fun bufferPositionToOffset(position: BufferPosition): Int {
    val starts = starts()
    val line = position.line.coerceIn(0, starts.size - 1)
    val column = position.column.coerceAtLeast(0)
    return (starts[line] + column).coerceIn(0, getLineEndOffset(line))
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

  /**
   * Inserts [text] at every caret and leaves each one after what it typed.
   *
   * Done here rather than caret by caret because the offsets move as it goes: inserting from the
   * end backwards keeps the earlier ones valid, and a caret then shifts by one insertion for each
   * caret at or before it - its own included.
   */
  fun typeAtCarets(text: String) {
    if (text.isEmpty()) return
    val sorted = vimCarets.sortedBy { it.offset }
    for (index in sorted.indices.reversed()) {
      buffer.insert(sorted[index].offset, text)
    }
    sorted.forEachIndexed { index, caret ->
      caret.moveToOffsetNative(caret.offset + text.length * (index + 1))
    }
  }

  /** Deletes the character before every caret, which is what backspace does in insert mode. */
  fun deleteBeforeCarets() {
    val sorted = vimCarets.sortedBy { it.offset }
    for (index in sorted.indices.reversed()) {
      val caret = sorted[index]
      if (caret.offset > 0) buffer.replace(caret.offset - 1, caret.offset, "")
    }
    sorted.forEachIndexed { index, caret ->
      // One character goes for each caret at or before this one that had something to delete.
      val deletionsBefore = sorted.take(index + 1).count { it.offset > 0 }
      caret.moveToOffsetNative((caret.offset - deletionsBefore).coerceAtLeast(0))
    }
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

  /**
   * What was last pushed to VS Code, so that the event it fires in response is not read back.
   *
   * Setting selections makes VS Code report a selection change, and it reports it later rather than
   * during the call - so a flag around the flush would not cover it. Comparing what arrived with
   * what was sent does: an event carrying exactly what this editor asked for is its own echo.
   */
  private var pushedSelections: List<Pair<Int, Int>> = emptyList()

  fun syncCaretsFromEditor() {
    val selections = nativeEditor.selections
    val document = nativeEditor.document
    val incoming = selections.map { document.offsetAt(it.anchor) to document.offsetAt(it.active) }
    if (incoming == pushedSelections) return

    vimCarets.clear()
    incoming.forEachIndexed { index, (anchor, active) ->
      val caret = VsCodeCaret(this, active, isPrimary = index == 0)
      if (anchor != active) {
        caret.setSelection(minOf(anchor, active), maxOf(anchor, active))
        caret.vimSelectionStart = anchor
      }
      vimCarets += caret
    }
    if (vimCarets.isEmpty()) vimCarets += VsCodeCaret(this, 0, isPrimary = true)
    pushedSelections = incoming
  }

  /**
   * Follows VS Code into and out of visual mode when the user works with the mouse.
   *
   * Dragging a selection in Vim *is* visual mode, so a host that left the mode alone would show a
   * selection that the next keystroke did not know about - `d` would delete a character instead of
   * the selection. Clicking to collapse it leaves visual mode for the same reason.
   */
  fun followSelectionIntoMode(): Boolean {
    val hasSelection = vimCarets.any { it.hasSelection() }
    val inVisual = mode is Mode.VISUAL
    return when {
      hasSelection && !inVisual -> {
        injector.visualMotionGroup.enterVisualMode(this, SelectionType.CHARACTER_WISE)
        true
      }

      !hasSelection && inVisual -> {
        mode = Mode.NORMAL()
        true
      }

      else -> false
    }
  }

  /**
   * Pushes the carets - and their selections - back to VS Code.
   *
   * A caret with a selection becomes a VS Code selection with the same two offsets: the engine
   * already speaks the editor's convention, with the end exclusive, which is why IntelliJ's host
   * passes them straight through as well. Vim's own inclusive end is converted inside the engine,
   * not here.
   */
  private fun flushCarets() {
    val document = nativeEditor.document
    // Primary first, because that is where VS Code takes its own primary from - `selections[0]`.
    // With a block drawn downwards the primary is the *last* caret in the document, and pushing
    // them in document order would leave the blinking caret at the top of the block.
    val primary = primaryCaret()
    val ordered = listOf(primary) + vimCarets.filter { it !== primary }
    val selections = ordered.map { caret ->
      if (caret.hasSelection()) {
        FlushedSelection(document.positionAt(caret.selectionStart), document.positionAt(caret.selectionEnd))
      } else {
        val position = document.positionAt(caret.offset)
        FlushedSelection(position, position)
      }
    }
    nativeEditor.selections = selections.toTypedArray()
    selections.firstOrNull()?.let { nativeEditor.selection = it }
    pushedSelections = selections.map {
      document.offsetAt(it.anchor) to document.offsetAt(it.active)
    }
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

  /**
   * The caret the engine treats as *the* caret, which is not always the first one.
   *
   * With one caret this is that caret. With a block selection it is the corner the motion moved:
   * a motion in block-visual mode runs on the primary caret alone, and the engine then rebuilds the
   * whole block around where it ended up. After `<C-V>k` that corner is the *top* of the block, so
   * anything that assumed the first caret in the document would drag the wrong corner.
   *
   * [vimCarets] stays in document order regardless, because every multi-caret edit depends on it -
   * `forEachNativeCaret(reverse = true)` is how a delete keeps earlier offsets valid.
   */
  override fun currentCaret(): VimCaret = primaryCaret()

  override fun primaryCaret(): VimCaret = vimCarets.firstOrNull { it.isPrimary } ?: vimCarets.first()

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

  /**
   * Mode lives in the state machine, not here.
   *
   * `VimEditorBase` reads and writes `injector.vimState`, and notifies the mode listeners on the
   * way - which is what makes a mode change visible to the key handler at all. An editor that
   * stored the mode in a field of its own would set it successfully and change nothing: the next
   * keystroke would still be interpreted in normal mode, and `iX` would run `X` as a command.
   */
  // The cast is what IntelliJ's host does too: `VimStateMachine` exposes the mode read-only, and
  // only the implementation the host installed can set it.
  override fun updateMode(mode: Mode) {
    (injector.vimState as VimStateMachineImpl).mode = mode
  }

  override fun updateIsReplaceCharacter(isReplaceCharacter: Boolean) {
    (injector.vimState as VimStateMachineImpl).isReplaceCharacter = isReplaceCharacter
  }

  override var vimChangeActionSwitchMode: Mode? = null
  override var insertMode: Boolean = false

  override fun isWritable(): Boolean = true
  override fun isDocumentWritable(): Boolean = true
  override fun isDisposed(): Boolean = false

  /** IntelliJ's one-line editors are inline input fields; VS Code has no such thing. */
  override fun isOneLineMode(): Boolean = false

  /** IntelliJ's live templates. VS Code's snippets are its own feature and are not wired up. */
  override fun isTemplateActive(): Boolean = false

  override fun hasUnsavedChanges(): Boolean = nativeEditor.document.isDirty

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

  // ---- More than one caret, which is Vim's blockwise Visual mode and nothing else here.

  override fun removeCaret(caret: VimCaret) {
    if (vimCarets.remove(caret)) caretListeners.forEach { it.caretRemoved(caret) }
  }

  override fun addCaret(offset: Int): VimCaret? {
    val caret = VsCodeCaret(this, offset.coerceIn(0, fileSize().toInt()), isPrimary = false)
    val at = vimCarets.indexOfFirst { it.offset > caret.offset }
    if (at < 0) vimCarets += caret else vimCarets.add(at, caret)
    return caret
  }

  /**
   * Back to one caret, keeping the primary - which is the one carrying the block's anchor.
   *
   * The engine calls this before rebuilding a block and again on the way out of Visual mode, and
   * both rely on the survivor being the same caret object each time: `vimSelectionStart` lives on
   * the instance, so a survivor chosen by position would lose the anchor the moment the block was
   * drawn upwards.
   */
  override fun removeSecondaryCarets() {
    val primary = primaryCaret()
    val removed = vimCarets.filter { it !== primary }
    if (removed.isEmpty()) return
    vimCarets.retainAll { it === primary }
    caretListeners.forEach { listener -> removed.forEach { listener.caretRemoved(it) } }
  }

  /**
   * A rectangle, as one caret per line each selecting that line's slice of it.
   *
   * This is IntelliJ's `setBlockSelection` and the engine is written to it: it clears the secondary
   * carets, asks for the block, and then walks `nativeCarets()` adjusting each one - so the carets
   * have to exist by the time it looks, and the primary has to be among them with its anchor
   * intact. Hence the reuse: the caret that was primary going in is placed on its own line rather
   * than replaced, which is the same trick IntelliJ's caret model plays when it reuses carets by
   * insertion order.
   *
   * A line shorter than the block contributes what it has, down to nothing. Vim does the same;
   * a rectangle over ragged text is not a rectangle.
   */
  override fun vimSetSystemBlockSelectionSilently(start: BufferPosition, end: BufferPosition) {
    val lastLine = (lineCount() - 1).coerceAtLeast(0)
    val firstBlockLine = minOf(start.line, end.line).coerceIn(0, lastLine)
    val lastBlockLine = maxOf(start.line, end.line).coerceIn(0, lastLine)
    val leftColumn = minOf(start.column, end.column).coerceAtLeast(0)
    val rightColumn = maxOf(start.column, end.column).coerceAtLeast(0)

    val survivor = vimCarets.firstOrNull { it.isPrimary } ?: vimCarets.firstOrNull()
    val survivorLine = survivor?.getBufferPosition()?.line?.coerceIn(firstBlockLine, lastBlockLine)

    val rebuilt = (firstBlockLine..lastBlockLine).map { line ->
      val lineStart = getLineStartOffset(line)
      val lineEnd = getLineEndOffset(line)
      val from = (lineStart + leftColumn).coerceAtMost(lineEnd)
      val to = (lineStart + rightColumn).coerceAtMost(lineEnd)
      val caret = if (survivor != null && line == survivorLine) survivor else VsCodeCaret(this, to, isPrimary = false)
      caret.moveToOffsetNative(to)
      caret.setSelection(from, to)
      caret
    }

    val removed = vimCarets.filter { existing -> rebuilt.none { it === existing } }
    vimCarets.clear()
    vimCarets += rebuilt
    caretListeners.forEach { listener -> removed.forEach { listener.caretRemoved(it) } }
  }

  /**
   * Who wants to hear that a caret went away.
   *
   * The engine registers one while running a motion for each of several carets, so that two carets
   * landing on the same place can be merged without the loop tripping over the one that vanished.
   * Block selections take a different path - a motion there runs on the primary caret alone - so in
   * practice this fires on the way out of a block, which is exactly when a listener wants to know.
   */
  private val caretListeners: MutableList<VimCaretListener> = mutableListOf()

  override fun addCaretListener(listener: VimCaretListener) {
    caretListeners += listener
  }

  override fun removeCaretListener(listener: VimCaretListener) {
    caretListeners -= listener
  }
  /**
   * Leaving insert mode, which the engine asks the *editor* to do because a host may have its own
   * insert state to unwind - IntelliJ drops a pending visual-mode timer here. VS Code has no such
   * state, so this is the engine's own `processEscape`, which is what IntelliJ ends up calling too:
   * it steps the caret back one and closes out the insert session.
   */
  override fun exitInsertMode(context: ExecutionContext) {
    injector.changeGroup.processEscape(this, context)
  }
  override fun exitSelectModeNative(adjustCaret: Boolean): Unit = TODO("VsCodeEditor.exitSelectModeNative")
  /** The last column on a line, which is its length while a visual line is a buffer line. */
  override fun getLastVisualLineColumnNumber(line: Int): Int =
    getLineEndOffset(line) - getLineStartOffset(line)
  override fun createIndentBySize(size: Int): String = TODO("VsCodeEditor.createIndentBySize")
  /**
   * Folds, of which this host can *do* several and *know* none.
   *
   * `za`, `zo`, `zc` and the recursive pair are commands - the engine asks the host for their names
   * and VS Code folds. The rest of Vim's fold vocabulary wants folds as data: which region is at
   * this line, how deep the nesting goes, remove this one. VS Code will not say. Folding ranges
   * come from a language server through `executeFoldingRangeProvider`, which is a promise, and
   * these are asked in the middle of a keystroke.
   *
   * So the answers here are the truthful ones for an editor that can fold but cannot look: no
   * region at any line, no nesting, and `zd`/`zD` report that they did nothing rather than claiming
   * to have removed something.
   */
  override fun getFoldRegionAtLine(line: Int): VimFoldRegion? = null

  /**
   * `zR` and `zM`, which the engine expresses as `'foldlevel'` rather than as a command.
   *
   * Level zero means every fold closed and anything above it means open, which - without knowing
   * the nesting - is exactly the two commands VS Code has. Since [getMaxFoldDepth] answers zero,
   * `zR` asks for level one and lands here as "open everything", which is what `zR` means.
   */
  override fun applyFoldLevel(foldLevel: Int) {
    val executor = injector.actionExecutor
    val action = if (foldLevel <= 0) executor.ACTION_COLLAPSE_ALL_REGIONS else executor.ACTION_EXPAND_ALL_REGIONS
    executor.executeAction(this, action, VsCodeExecutionContext)
  }

  override fun getMaxFoldDepth(): Int = 0

  override fun createFoldRegion(startOffset: Int, endOffset: Int, collapse: Boolean): VimFoldRegion? = null
  override fun deleteFoldRegionAtOffset(offset: Int): Boolean = false
  override fun deleteFoldRegionsRecursivelyAtOffset(offset: Int): Boolean = false
  override val lfMakesNewLine: Boolean get() = TODO("VsCodeEditor.lfMakesNewLine")
  override val indentConfig: VimIndentConfig get() = TODO("VsCodeEditor.indentConfig")
  /**
   * What `R` overwrote, so that backspace in replace mode puts it back. Held per editor because
   * replace mode is per window; the engine builds and clears it.
   */
  override var replaceMask: VimEditorReplaceMask? = null
  /** What the last visual selection was, which is what `gv` restores and `p` consults. */
  override var vimLastSelectionType: SelectionType? = null
  /** Markers and change notifications, both of which the buffer is the only exact source for. */
  override fun createLiveMarker(start: Int, end: Int): LiveRange = buffer.createMarker(start, end)

  override val document: VimDocument = object : VimDocument {
    override fun addChangeListener(listener: ChangesListener) = buffer.addChangeListener(listener)
    override fun removeChangeListener(listener: ChangesListener) = buffer.removeChangeListener(listener)

    /** IntelliJ's read-only fragments, which VS Code has no equivalent of. */
    override fun getOffsetGuard(offset: Int): LiveRange? = null
    override fun getRangeGuard(start: Int, end: Int): LiveRange? = null
  }
}

/** A collapsed selection to hand back to VS Code, which has no separate notion of a caret. */
private class FlushedSelection(
  override val anchor: Position,
  override val active: Position,
) : Selection
