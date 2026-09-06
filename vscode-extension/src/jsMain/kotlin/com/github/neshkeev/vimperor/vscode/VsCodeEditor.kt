/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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
import com.maddyhome.idea.vim.api.VimFoldRegion
import com.maddyhome.idea.vim.api.VimIndentConfig
import com.maddyhome.idea.vim.api.VimScrollingModel
import com.maddyhome.idea.vim.api.VimVirtualFile
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.api.VirtualBufferKind
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ChangesListener
import com.maddyhome.idea.vim.common.LiveRange
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.VimEditorReplaceMask
import com.maddyhome.idea.vim.group.visual.VisualChange
import com.maddyhome.idea.vim.helper.isEndAllowed
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.SelectionType
import kotlin.math.abs

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

  /**
   * Where Vim believes the top of the window is, and where VS Code last said it was.
   *
   * Vim owns the view: `<C-E>` means "the window is now one line further down", and the next
   * `<C-E>` has to build on that. This host asked `visibleRanges` instead, which is VS Code's
   * answer to a different question - where the view has been *painted* - and in a real window that
   * answer did not follow a scroll at all. `oldTop` stayed 0 forever, so every `<C-E>` recomputed
   * the same target and `<C-Y>` clamped to 0 and refused. Both keys did nothing, on a 924-line
   * file, with the view reporting `0..13` after every press.
   *
   * So the intention is remembered here and believed until VS Code contradicts it. A reported top
   * that differs from the last one seen is the editor saying where it actually is - because the
   * user scrolled, or because it caught up - and that is adopted. Anything else keeps Vim's answer.
   */
  /**
   * The last value `'wrap'` wrote for this editor, so that it is written once rather than per key.
   *
   * The configuration read cannot serve: it answers from a value VS Code has not finished updating
   * in the same turn, so the keystroke after `:set wrap` still saw `off` and wrote `on` again.
   */
  internal var wroteWordWrap: Boolean? = null

  internal var believedTopLine: Int? = null
  internal var lastReportedTopLine: Int? = null

  /**
   * The viewport VS Code last described, remembered from the moment a command rewrote the document.
   *
   * `visibleRanges` is clipped to the text, so a one-line file reports one visible line however
   * tall the window is - and the height this host works in is derived from it. That is harmless
   * until the document *grows underneath the report*: a command that reformats one line of JSON
   * into fifteen leaves the ranges saying `0..0`, so the window looks one line tall, and the next
   * caret correction dutifully scrolls line fourteen to the top of a window that could have shown
   * the whole file. The first line goes up behind the tab bar, and every later `zb`, `gg` and
   * `<C-E>` reasons from the same wrong height.
   *
   * Null means the report can be trusted. Non-null means it describes the document as it was
   * before the command, and is worth nothing until VS Code reports something else.
   */
  internal var viewportReportedBeforeEdit: Pair<Int, Int>? = null

  /**
   * Every scroll this host asked for since the last keystroke, for the trace.
   *
   * A window has now three times disagreed with what this host believed a scroll did, and each time
   * it was this log that said so. So the asks themselves are recorded - where the view was believed
   * to be, where it was sent, and what the editor was reporting at the time - rather than reasoned
   * about from a distance.
   */
  internal val scrollLog: MutableList<String> = mutableListOf()

  /**
   * What was last pushed to VS Code, so that the event it fires in response is not read back.
   *
   * Setting selections makes VS Code report a selection change, and it reports it later rather than
   * during the call - so a flag around the flush would not cover it. Comparing what arrived with
   * what was sent does: an event carrying exactly what this editor asked for is its own echo.
   *
   * Declared *before* the initialiser that reads it, which is not a style preference. Kotlin/JS
   * assigns properties in declaration order, and [syncCaretsFromEditor] runs from `init` - so with
   * this further down the class it is plain `undefined` on the first call. Comparing against
   * undefined merely answered false; calling a method on it throws, which is how it was found.
   */
  private var pushedSelections: List<Pair<Int, Int>> = emptyList()

  init {
    syncCaretsFromEditor()
    // What IntelliJ gets from a document listener: the line about to change, before it does. Only
    // single-line changes, mirroring Vim's `u_save`, which calls `u_saveline` for a one-line range
    // and nothing else.
    buffer.beforeChange = { start, end, newText ->
      val startLine = offsetToBufferPosition(start).line
      if (startLine == offsetToBufferPosition(end).line && !newText.contains('\n')) {
        injector.lineChange.snapshotLine(startLine, this)
      }
    }
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

  /**
   * Whether this editor is Vim's command-line window, and which flavour of it.
   *
   * The engine asks in two places that matter: `<CR>` runs the line rather than moving down, and
   * `q:` inside `q:` reports E1292 rather than nesting. Both go through this, and both answer null
   * for every ordinary editor.
   *
   * Read from [VsCodeVirtualBuffers] rather than held here, so that one thing knows what is open.
   * An editor is created and thrown away whenever VS Code hands out a new `TextEditor` for the same
   * document - moving a file to a split is enough - and a flag on the editor would go with it.
   */
  override fun getVirtualBufferKind(): VirtualBufferKind? = virtualBuffers()?.kindOf(identity)

  /** The editor `q:` was opened from, which is where `<CR>` runs the line. */
  override fun getCmdwinOriginalEditor(): VimEditor? = virtualBuffers()?.originalEditorFor(identity)

  private fun virtualBuffers(): VsCodeVirtualBuffers? =
    injector.virtualBufferGroup as? VsCodeVirtualBuffers

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

  /**
   * A trailing newline *does* open a line, and this used to say the opposite.
   *
   * The comment that was here claimed a trailing newline opens no line and that Vim and VS Code
   * agree about it. Neither half is true of the thing that matters: an editor buffer is not a file,
   * and both IntelliJ and VS Code show a final empty line after a trailing newline and let a caret
   * sit on it. The engine is built on IntelliJ's document, so it expects that line to exist - and
   * without it `cc` and `dd` on the last line took the range of the line above, and `G` and `j`
   * stopped one line short. Seven of IdeaVim's own fixtures say so.
   */
  override fun nativeLineCount(): Int = if (buffer.text.isEmpty()) 0 else starts().size

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
    shiftCaretsAfterEdit(atPosition, atPosition, text.length)
  }

  override fun replaceString(start: Int, end: Int, newString: String) {
    buffer.replace(start, end, newString)
    shiftCaretsAfterEdit(start, end, newString.length)
  }

  override fun deleteString(range: TextRange) {
    buffer.replace(range.startOffset, range.endOffset, "")
    shiftCaretsAfterEdit(range.startOffset, range.endOffset, 0)
  }

  /**
   * Keeps every caret pointing at the same text after the engine has edited somewhere.
   *
   * IntelliJ gets this for free: a `Caret` is a document marker and the document moves it. Here a
   * caret is a value this host holds, so with more than one caret every edit but the first leaves
   * the others describing a document that has changed underneath them. Nothing noticed until
   * `multiple-cursors` arrived and `c` over two `<C-n>` cursors ended in `'start' is out of bounds`
   * - one caret was still pointing past the end of a document the other had just shortened.
   *
   * [typeAtCarets] and [deleteSelections] do their own arithmetic and do not come through here, for
   * the reason their own comments give: they edit at *every* caret and have to work backwards.
   */
  private fun shiftCaretsAfterEdit(start: Int, end: Int, newLength: Int) {
    val delta = newLength - (end - start)
    if (delta == 0) return
    vimCarets.forEach { it.adjustForEdit(start, end, delta) }
  }

  /**
   * Typed text, at every caret - replacing a selection, or overwriting in replace mode.
   *
   * Done here rather than caret by caret because the offsets move as it goes: writing from the end
   * backwards keeps the earlier ones valid, and a caret then shifts by the length difference of
   * every span at or before it - its own included.
   *
   * Three cases, and only the first is what an editor does without being asked. A caret with a
   * selection replaces it, which is how typing works everywhere and is the whole of what Vim's
   * Select mode is: `gh` and then a letter puts that letter where the selection was. IntelliJ's
   * typed action does it without IdeaVim asking, which is why nothing in the engine says so.
   *
   * `insertMode` is the second. It is the engine's own flag, and IntelliJ does not need to be told
   * about it either: its editor has an insert/overwrite mode of its own and the platform's typing
   * honours it. VS Code has no such mode, so the overwrite is done here - and only up to the end of
   * the line, because past that there is nothing to overwrite and Vim appends rather than eating
   * the line break and the line below, which is what `R` at the end of a short line does every time
   * somebody types a long word. A line break never overwrites: Vim's Enter in replace mode opens a
   * line like it does anywhere else, and the engine turns `insertMode` back on around it for the
   * same reason.
   */
  fun typeAtCarets(text: String) {
    if (text.isEmpty()) return
    val overwriting = !insertMode && !text.contains('\n')
    val spans = vimCarets.associateWith { caret ->
      if (caret.hasSelection()) {
        caret.selectionStart to caret.selectionEnd
      } else {
        val from = caret.offset
        val lineEnd = getLineEndOffset(offsetToBufferPosition(from).line)
        from to if (overwriting) minOf(from + text.length, lineEnd).coerceAtLeast(from) else from
      }
    }
    val sorted = vimCarets.sortedBy { spans.getValue(it).first }
    for (caret in sorted.reversed()) {
      val (from, to) = spans.getValue(caret)
      if (to > from) buffer.replace(from, to, text) else buffer.insert(from, text)
    }
    var shift = 0
    for (caret in sorted) {
      val (from, to) = spans.getValue(caret)
      caret.removeSelection()
      caret.moveToOffsetNative(from + shift + text.length)
      shift += text.length - (to - from)
    }
  }

  /**
   * Deletes what is selected at every caret, leaving each one where its selection began.
   *
   * This is Delete and Backspace in Select mode, and the engine reaches it the long way round: it
   * asks the host what its *own* editor has bound to those keys and runs that, on the grounds that
   * a host may have something else to do with them. In IntelliJ the answer is `EditorDelete`, which
   * deletes a selection because that is what Delete does in any editor. VS Code's equivalent is a
   * command and therefore asynchronous, so this is the same behaviour done here.
   */
  fun deleteSelections() {
    val sorted = vimCarets.filter { it.hasSelection() }.sortedBy { it.selectionStart }
    if (sorted.isEmpty()) return
    val spans = sorted.map { it.selectionStart to it.selectionEnd }
    for (index in sorted.indices.reversed()) {
      val (from, to) = spans[index]
      buffer.replace(from, to, "")
    }
    var deletedBefore = 0
    sorted.forEachIndexed { index, caret ->
      val (from, to) = spans[index]
      caret.removeSelection()
      caret.moveToOffsetNative(from - deletedBefore)
      deletedBefore += to - from
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

  /**
   * Enter, carrying the indent of the line it was pressed on to the new line.
   *
   * This is what `o`, `O` and `cc` reach through `runEnterAction`, and the indent is the whole
   * reason they go through the host at all: the engine's own comment on `O` says it goes to the end
   * of the previous line and presses Enter because "we get better indent positioning ... especially
   * with plain text files". IntelliJ's `EditorEnter` indents; so does VS Code's own Enter; this host
   * wrote a bare newline, so `o` on an indented line started the new one in column zero.
   *
   * Per caret, because with more than one they are on different lines and the indent is the line's.
   *
   * A selection is replaced rather than pushed along, which is what Enter means in Select mode -
   * `gh<CR>` deletes the selected character and opens a line where it was.
   */
  fun insertNewLineAtCarets() {
    val spans = vimCarets.associateWith { caret ->
      if (caret.hasSelection()) caret.selectionStart to caret.selectionEnd else caret.offset to caret.offset
    }
    val texts = vimCarets.associateWith { caret ->
      "\n" + indentOfLine(offsetToBufferPosition(spans.getValue(caret).first).line)
    }
    val sorted = vimCarets.sortedBy { spans.getValue(it).first }
    for (caret in sorted.reversed()) {
      val (from, to) = spans.getValue(caret)
      if (to > from) buffer.replace(from, to, texts.getValue(caret)) else buffer.insert(from, texts.getValue(caret))
    }
    var shift = 0
    for (caret in sorted) {
      val (from, to) = spans.getValue(caret)
      val text = texts.getValue(caret)
      caret.removeSelection()
      caret.moveToOffsetNative(from + shift + text.length)
      shift += text.length - (to - from)
    }
  }

  /** The leading whitespace of [line], which is what a new line below it starts with. */
  private fun indentOfLine(line: Int): String {
    val start = getLineStartOffset(line)
    val end = getLineEndOffset(line)
    val text = buffer.text
    var index = start
    while (index < end && (text[index] == ' ' || text[index] == '\t')) index++
    return text.substring(start, index)
  }

  /**
   * Deletes the character *after* every caret, which is the Delete key.
   *
   * Nothing types it - Vim's own `x` and `<Del>` are the engine's - and it exists because a repeat
   * replays one. `VimChangeGroupBase` records an insert as the document changes it made, and a
   * change that removed text is recorded as `nativeActionManager.deleteAction` once per character
   * removed, run after the caret has been moved to where the removal started. So `ce` then
   * `foo<BS><BS><BS>foo` is recorded as "type foo, delete three, type foo", and a host that
   * answered null for that action recorded only the typing: `.` replayed `foofoo`.
   */
  fun deleteAtCarets() {
    val end = fileSize().toInt()
    val sorted = vimCarets.sortedBy { it.offset }
    for (index in sorted.indices.reversed()) {
      val caret = sorted[index]
      if (caret.offset < end) buffer.replace(caret.offset, caret.offset + 1, "")
    }
    sorted.forEachIndexed { index, caret ->
      // One character goes for each deletion before this caret; its own leaves it where it is.
      val deletionsBefore = sorted.take(index).count { it.offset < end }
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
    mergeIndistinguishableCarets()
    buffer.flush { applied ->
      if (applied) flushCarets() else syncCaretsFromEditor()
      onResult(applied)
    }
  }

  /**
   * Collapses carets that have stopped being different carets.
   *
   * Every host with more than one caret does this and the engine leans on it, which is why nothing
   * here says so out loud: `MotionActionHandler.CaretMergingWatcher` exists only to keep a per-caret
   * loop upright *while* the host removes one underneath it. IntelliJ's caret model merges as part
   * of setting selections; VS Code's does too when the array is assigned. This host kept its own
   * list and merged nothing, so switching a two-line block to line Visual with `V` left two carets
   * holding one selection between them - `caret expected [46], actual [46, 75]` - and `Ve` over two
   * carets on the same line left three where IdeaVim has two.
   *
   * Two rules, and the narrow one is deliberate. Selections merge when they genuinely *overlap*, not
   * when they touch: two linewise selections on adjacent lines meet at the newline and IdeaVim keeps
   * them apart - measured, `carets [15, 48] selections [(13, 44), (44, 85)]`. Carets without
   * selections merge when they stand in the same place, because there is nothing left to tell them
   * apart.
   *
   * The earlier caret survives, which is what IdeaVim answers with, and it takes the union of the
   * two selections and the primary flag if either had it.
   */
  /**
   * The primary caret's [VsCodeCaret.vimLastVisualOperatorRange], kept where a new primary can find
   * it. See that property; this is the editor half of IdeaVim's `userDataCaretToEditor`.
   */
  internal var lastVisualOperatorRange: VisualChange? = null

  private fun mergeIndistinguishableCarets() {
    if (vimCarets.size < 2) return
    val kept = mutableListOf<VsCodeCaret>()
    val removed = mutableListOf<VsCodeCaret>()
    for (caret in vimCarets) {
      val previous = kept.lastOrNull()
      if (previous == null || !indistinguishable(previous, caret)) {
        kept += caret
        continue
      }
      if (previous.hasSelection() && caret.hasSelection()) {
        previous.setSelection(
          minOf(previous.selectionStart, caret.selectionStart),
          maxOf(previous.selectionEnd, caret.selectionEnd),
        )
      }
      if (caret.isPrimary) previous.isPrimary = true
      removed += caret
    }
    if (removed.isEmpty()) return
    vimCarets.clear()
    vimCarets += kept
    caretListeners.forEach { listener -> removed.forEach { listener.caretRemoved(it) } }
  }

  private fun indistinguishable(first: VsCodeCaret, second: VsCodeCaret): Boolean = when {
    first.hasSelection() && second.hasSelection() ->
      minOf(first.selectionEnd, second.selectionEnd) > maxOf(first.selectionStart, second.selectionStart)

    !first.hasSelection() && !second.hasSelection() -> first.offset == second.offset
    else -> false
  }

  // ---- Carets. VS Code calls them selections; a collapsed selection is a plain caret.

  fun syncCaretsFromEditor() {
    val selections = nativeEditor.selections
    val incoming = selections.map { offsetOf(it.anchor) to offsetOf(it.active) }
    // As a set, because the order is not this host's to rely on. Carets are pushed primary first,
    // and VS Code is free to report them back in document order - so comparing lists would call a
    // block selection's own echo a user edit and rebuild every caret from it.
    if (incoming.toSet() == pushedSelections.toSet()) return

    // What the outgoing primary knew, to be handed to the incoming one.
    //
    // IdeaVim mirrors seven of these onto the *editor* - `userDataCaretToEditor` - so that a caret
    // being replaced does not take them with it. This host had needed one of the seven until now.
    // `lastSelectionInfo` is what `gv` restores, and rebuilding the carets after an undo threw it
    // away: `yankring`'s `<C-P>` undoes its paste, reselects with `gv`, and pastes the older entry
    // over the selection - so with no selection to restore it inserted into the middle of the word
    // it should have replaced.
    val outgoing = vimCarets.firstOrNull { it.isPrimary }

    vimCarets.clear()
    // Document order, whatever order VS Code reported them in. `selections[0]` is the primary and it
    // is under no obligation to be the first caret in the file - alt-clicking upwards puts it last -
    // while everything that walks the carets to make an edit depends on the order being the
    // document's. So the flag follows index zero and the list is sorted around it.
    incoming.sortedBy { (_, active) -> active }.forEach { (anchor, active) ->
      val caret = VsCodeCaret(this, active, isPrimary = (anchor to active) == incoming.first())
      // Where the caret now is, is the column `j` and `k` should aim for. This is the one way a
      // caret moves that the engine never hears about - a click or a drag - so it is the one place
      // the host has to reset `curswant` itself. Without it a fresh caret remembers column zero and
      // the first `k` after a click goes to the start of the line.
      caret.resetLastColumn()
      if (anchor != active) {
        caret.setSelection(minOf(anchor, active), maxOf(anchor, active))
        caret.vimSelectionStart = anchor
      }
      vimCarets += caret
    }
    if (vimCarets.isEmpty()) vimCarets += VsCodeCaret(this, 0, isPrimary = true)
    // Only what a fresh caret has no way of knowing. The offset, the selection and the remembered
    // column all come from what VS Code just reported and must not be carried over.
    outgoing?.let { previous ->
      vimCarets.firstOrNull { it.isPrimary }?.lastSelectionInfo = previous.lastSelectionInfo
    }
    pushedSelections = incoming
  }

  /**
   * Throws away a selection a host command left behind, when the engine is not in a mode that has
   * one.
   *
   * Vim has a selection in visual and select modes and nowhere else. `editor.action.formatSelection`
   * - reached from an `xnoremap`, which leaves visual mode on its way to the command line - finishes
   * with its range still selected, and by then the engine is back in normal mode. Adopting that
   * left a selection nothing could clear: `<Esc>` and `<C-c>` do not clear selections in normal
   * mode, because in Vim there is never one there to clear, and every later flush pushed it back.
   *
   * Only for commands. A *drag* is the user selecting text, and that legitimately puts the engine
   * into visual mode - which is why this is not part of [syncCaretsFromEditor], where it would
   * have stopped the mouse from entering visual mode at all.
   */
  fun dropSelectionLeftByCommand() {
    if (mode is Mode.VISUAL || mode is Mode.SELECT) return
    if (vimCarets.none { it.hasSelection() }) return
    // To the start of what was selected, not to wherever the selection's active end was. VS Code's
    // undo restores the selection a change was made over - `Vj<C-X>` then `u` gives back two
    // selected lines - and Vim's `u` is not Visual mode: it leaves the caret on the first line of
    // the change. Five fixtures, all of them `V`-mode operators followed by `u`.
    vimCarets.forEach {
      if (it.hasSelection()) it.moveToOffsetNative(it.selectionStart)
      it.removeSelection()
    }
    // Pushed back rather than left for the next keystroke, which would mean the user looking at a
    // highlight that no key can act on.
    flushCarets()
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
  /**
   * Takes over the carets of the wrapper this one replaces, for the same document.
   *
   * VS Code hands out a new `TextEditor` when a tab is shown again, and it restores that tab's view
   * state - the scroll position and the selection - *after* it reports the editor as active. So the
   * editor this is built from can still say 0,0, and reading it was how switching to another tab
   * and back put the caret on the first line: the host snapshotted a position VS Code had not
   * restored yet, and the next flush wrote it back and made it true.
   *
   * The wrapper is still replaced rather than repaired - it points at an editor VS Code is no
   * longer using - but the carets are not the wrapper's, they are the *document's*, and the
   * document is the same one. IntelliJ keeps them for the same reason without having to be asked:
   * its editor survives the tab switch.
   *
   * A real move made while the tab was in the background arrives as a selection change VS Code
   * attributes to the user, and [VimHost.selectionChanged] takes those. This only decides what to
   * believe in the moment before anything has said otherwise.
   */
  internal fun adoptCaretsFrom(previous: VsCodeEditor) {
    if (previous.vimCarets.isEmpty()) return
    val limit = text().length
    vimCarets.clear()
    previous.vimCarets.forEach { old ->
      val caret = VsCodeCaret(this, old.offset.coerceIn(0, limit), isPrimary = old.isPrimary)
      if (old.hasSelection()) {
        caret.setSelection(old.selectionStart.coerceIn(0, limit), old.selectionEnd.coerceIn(0, limit))
        caret.vimSelectionStart = old.vimSelectionStart.coerceIn(0, limit)
      }
      // What `gv` restores, which a fresh caret has no way of knowing. See [syncCaretsFromEditor].
      caret.lastSelectionInfo = old.lastSelectionInfo
      vimCarets += caret
    }
    // Written out, because the editor on screen is the one that is wrong.
    flushCarets()
  }

  private fun flushCarets() {
    // A command is waiting to read the selection `selectForHostCommand` put there. Pushing the
    // carets now would replace it with a collapsed caret and the command would act on the wrong
    // range - see `selectForHostCommand`. The host flushes again once the command has landed.
    if (selectionHandedToHostCommand) return

    // Primary first, because that is where VS Code takes its own primary from - `selections[0]`.
    // With a block drawn downwards the primary is the *last* caret in the document, and pushing
    // them in document order would leave the blinking caret at the top of the block.
    val primary = primaryCaret()
    val ordered = listOf(primary) + vimCarets.filter { it !== primary }
    val selections = ordered.map { caret ->
      if (caret.hasSelection()) {
        Selection(positionOf(caret.selectionStart), positionOf(shownEnd(caret.selectionStart, caret.selectionEnd)))
      } else {
        val position = positionOf(caret.offset)
        Selection(position, position)
      }
    }
    // `selections` and nothing else. Assigning `selection` afterwards looks like setting the primary
    // and is not: VS Code documents it as "shorthand for TextEditor.selections[0]", and its setter
    // is `this._selections = [value]` - so it *discards* every other caret. A block selection was
    // therefore pushed correctly and then thrown away one line at a time, on every flush. Nothing
    // offline could see it, because the fake and the stub both keep `selection` as a plain field
    // rather than as the shorthand it is. The primary is already first in this list, which is where
    // VS Code takes it from.
    nativeEditor.selections = selections.toTypedArray()
    pushedSelections = selections.map { offsetOf(it.anchor) to offsetOf(it.active) }
    revealCaretColumn()
  }

  /**
   * Scrolls sideways far enough to show the caret, which nothing else here does.
   *
   * VS Code keeps its own cursor on screen when *it* moves the cursor. It does not when an
   * extension assigns `selections`, and this host assigns them on every keystroke - so `$` on a
   * line wider than the window moved the caret to the end and left the window where it was,
   * looking at the beginning.
   *
   * `revealRange` is the API this file's viewport code deliberately does not use, and the reason
   * does not apply here. That was about vertical scrolling, where a reveal is a *request* about
   * where a line should end up and a real window answered `AtTop` five lines out; Vim's vertical
   * arithmetic goes through `editorScroll` instead. Horizontally there is no arithmetic to do and
   * nothing to do it with: `visibleRanges` carries lines and no columns, so the column the window
   * starts at cannot be read, and a reveal is the only way to move it at all.
   *
   * Guarded on the caret's line already being on screen, which keeps the two axes apart. `Default`
   * scrolls as little as it can, so with the line visible the only axis left for it to move is the
   * horizontal one, and the vertical position stays whatever `zt`, `zz` or `<C-E>` last made it.
   */
  private fun revealCaretColumn() {
    val position = positionOf(primaryCaret().offset)
    // Asked for as rarely as it can be, because `Default` is not the horizontal-only operation this
    // wants it to be. Measured from a trace of a real window: with all sixteen lines of a document
    // showing, `G` revealed the last line and VS Code scrolled *down* two lines to leave room under
    // it - `view=[0..15]` became `view=[2..15]` - so the first two lines went off the top and every
    // `zb` afterwards reasoned from a window that had been made two lines shorter than it is.
    //
    // A caret inside the first screenful of columns cannot be off the right-hand edge, unless an
    // earlier reveal scrolled the view there - which is what [revealedForColumn] remembers, so the
    // journey back from a long line still gets its one reveal.
    //
    // 80 is `getApproximateScreenWidth`, which this host does not measure: VS Code exposes no
    // horizontal viewport to measure. It is a threshold, not a measurement, and it is only allowed
    // to be wrong in the cheap direction - too small costs a reveal nobody needed.
    val far = position.character >= injector.engineEditorHelper.getApproximateScreenWidth(this)
    if (!far && !revealedForColumn) return
    val onScreen = nativeEditor.visibleRanges.any { position.line >= it.start.line && position.line <= it.end.line }
    if (!onScreen) return
    revealedForColumn = far
    revealPrimaryCaret()
  }

  /** Whether a reveal has scrolled the view off column zero, and so whether coming back needs one. */
  private var revealedForColumn = false

  /**
   * The same reveal without the on-screen guard, for when the guard has nothing to guard with.
   *
   * After a command rewrites the document, `visibleRanges` still describes the text that was there
   * before it, so "is the caret's line on screen" is a question about the wrong document. This asks
   * VS Code to put the caret in view and to work out for itself how far that is - which it can and
   * this host cannot, because the window's real height is not something an extension can read.
   */
  internal fun revealPrimaryCaret() {
    val position = positionOf(primaryCaret().offset)
    nativeEditor.revealRange(Range(position, position), TextEditorRevealType.Default)
  }

  /**
   * Where a selection should be drawn as ending, which is not always where it ends.
   *
   * VS Code draws the cursor at a selection's `active` end, and a *linewise* selection ends at the
   * start of the line after the last one it covers - so `V` on a single line drew the cursor on
   * the line below it, one line further down than Vim's. Pulling the drawn end back to the end of
   * the last covered line puts the cursor back on that line.
   *
   * Only what is *drawn* moves. The engine's own selection is untouched, so `d` over a linewise
   * selection still takes the newline with it.
   *
   * By line rather than by stepping back one character: on a CRLF document `end - 1` is the offset
   * between the carriage return and the newline, and a cursor there is a cursor in the middle of a
   * line ending.
   */
  private fun shownEnd(start: Int, end: Int): Int {
    if (end <= start) return end
    val position = positionOf(end)
    if (position.character != 0 || position.line == 0) return end
    return maxOf(start, getLineEndOffset(position.line - 1))
  }

  /**
   * The two conversions between an engine offset and a VS Code position, through the buffer.
   *
   * Not `document.offsetAt` and `document.positionAt`, for two reasons that both come down to the
   * document being the wrong thing to ask. It is a *document* offset, which on a CRLF file counts
   * one carriage return per line and so is not the offset the engine is holding - every caret would
   * drift a line further off with each line above it. And the document lags the buffer, so a
   * conversion made while an edit is still in flight would answer about text the engine has already
   * moved past. The buffer's line index has neither problem, and a line and column mean the same
   * thing on both sides whichever way the file ends its lines.
   */
  /**
   * Puts VS Code's own selection over [ranges], for a command that acts on whatever is selected.
   *
   * `editor.action.reindentselectedlines` takes no argument - it reads the focused editor's
   * selection - so a range has to be handed over this way before the command runs.
   *
   * Written straight to `selections` rather than by moving the engine's carets, and that is the
   * point: the carets are Vim's state, and `=` ends in normal mode with no selection. Borrowing a
   * visual selection to describe the range would mean the engine believing in one afterwards.
   *
   * It also has to *survive* until the command runs, which is what [selectionHandedToHostCommand]
   * is for. The keystroke that asks for the command finishes by flushing its carets, and that flush
   * would push a collapsed caret over this selection before VS Code had read it - so `=` in normal
   * mode (`==`, `=j`, `=ap`) re-indented whatever line the caret was on rather than the range, and
   * only the visual-mode form worked, because there the carets happened to describe the same range.
   */
  internal fun selectForHostCommand(ranges: List<TextRange>) {
    if (ranges.isEmpty()) return
    nativeEditor.selections = ranges
      .map { Selection(positionOf(it.startOffset), positionOf(it.endOffset)) }
      .toTypedArray()
    selectionHandedToHostCommand = true
  }

  /**
   * Whether [selectForHostCommand] has handed VS Code a selection that a pending command still has
   * to read. Cleared by the host once the command has landed.
   */
  internal var selectionHandedToHostCommand: Boolean = false

  private fun positionOf(offset: Int): Position {
    val position = offsetToBufferPosition(offset)
    return Position(position.line, position.column)
  }

  private fun offsetOf(position: Position): Int =
    bufferPositionToOffset(BufferPosition(position.line, position.character))

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
  /**
   * Whether typing inserts or overwrites. True unless the engine puts the editor in replace mode -
   * and true to begin with, because an editor that has never been in replace mode inserts.
   */
  override var insertMode: Boolean = true

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
   * a rectangle over ragged text is not a rectangle - and a line that can contribute *nothing* gets
   * no caret at all, which is the part that is easy to miss. `<C-V>ljj` over a middle line as long
   * as the block's left edge leaves two carets, not three, and the same rule is what collapses
   * `<C-V>j` onto an empty line to a single caret. The exception is a block with no width yet -
   * `<C-V>` before any motion - where every line keeps its caret because there is no slice to fail
   * to hold.
   */
  override fun vimSetSystemBlockSelectionSilently(start: BufferPosition, end: BufferPosition) {
    val lastLine = (lineCount() - 1).coerceAtLeast(0)
    val firstBlockLine = minOf(start.line, end.line).coerceIn(0, lastLine)
    val lastBlockLine = maxOf(start.line, end.line).coerceIn(0, lastLine)
    val leftColumn = minOf(start.column, end.column).coerceAtLeast(0)
    val rightColumn = maxOf(start.column, end.column).coerceAtLeast(0)
    // The column every caret of the block stands in, which is the *active* corner's - not the
    // block's right edge. The two are the same only while the block is being drawn rightwards, and
    // that is why the difference went unnoticed: `<C-V>jl` reads the same either way. `<C-V>bjj`
    // does not. A block drawn leftwards puts the caret on the left edge of every line, and this
    // host put it on the right - `caret expected [15, 46, 87], actual [21, 52, 87]`, the primary
    // right because the engine drags it to the active corner afterwards and the others six columns
    // out, which is the width of the `b`.
    //
    // `end` is already the active corner: `blockToNativeSelection` widens whichever side is the
    // block's right edge by one, so for a rightward block this is one past the last selected
    // character and the engine's own "move one char left if the caret is on the selection end" step
    // brings it back.
    val caretColumn = end.column.coerceAtLeast(0)
    // Whether the block has a width at all, which decides what an empty slice means. With width, a
    // line that cannot hold the slice drops out; without it - `<C-V>` before any motion, or on an
    // empty line - every line keeps its caret, because nothing was asked of the line to begin with.
    val hasWidth = leftColumn != rightColumn

    val survivor = vimCarets.firstOrNull { it.isPrimary } ?: vimCarets.firstOrNull()
    val survivorLine = survivor?.getBufferPosition()?.line?.coerceIn(firstBlockLine, lastBlockLine)
    // The block's *active* end - the corner the last motion moved - as opposed to its anchor.
    val activeLine = end.line.coerceIn(firstBlockLine, lastBlockLine)

    val rebuilt = (firstBlockLine..lastBlockLine).mapNotNull { line ->
      val lineStart = getLineStartOffset(line)
      val lineEnd = getLineEndOffset(line)
      val from = (lineStart + leftColumn).coerceAtMost(lineEnd)
      val to = (lineStart + rightColumn).coerceAtMost(lineEnd)
      if (from == to && hasWidth) return@mapNotNull null
      val at = (lineStart + caretColumn).coerceAtMost(lineEnd)
      val caret = if (survivor != null && line == survivorLine) survivor else VsCodeCaret(this, at, isPrimary = false)
      caret.moveToOffsetNative(at)
      caret.setSelection(from, to)
      line to caret
    }.ifEmpty {
      // Every line of the block too short to hold it, which a block can be after `$` on a ragged
      // file. Dropping the last caret would leave the editor with none, so the active line keeps
      // one with nothing selected.
      val lineStart = getLineStartOffset(activeLine)
      val at = (lineStart + caretColumn).coerceAtMost(getLineEndOffset(activeLine))
      val caret = survivor ?: VsCodeCaret(this, at, isPrimary = false)
      caret.moveToOffsetNative(at)
      caret.setSelection(at, at)
      listOf(activeLine to caret)
    }

    // Which of them the engine will call "the caret", and it is not free to choose.
    //
    // `setVisualSelection` ends with `editor.primaryCaret().moveToInlayAwareOffset(selectionEnd)`,
    // where `selectionEnd` is the block's active corner - so whichever caret answers `primaryCaret`
    // is about to be dragged there. This host used to keep the flag on the caret it reused, which
    // is the one standing on the block's *first* line, so `<C-V>j` moved the top line's caret to
    // the bottom of the block: `caret expected [15, 46], actual [46, 46]`. IntelliJ's selection
    // model replaces every caret here and makes the one at `end` primary, which the engine's own
    // comment on that call describes - "WARNING! This can invalidate the primary caret".
    //
    // The anchor goes with the flag. `vimSelectionStart` is stored on the instance and is read from
    // the primary, so handing the flag to a different caret without it would lose the corner the
    // block is being drawn from.
    // The remembered column survives with the anchor, and for the same reason: the engine reads it
    // off the primary to decide where the block's edge belongs, and laying the block out moves every
    // caret onto a line of its own - which on a short line means a clamped column that is not what
    // the user is aiming at. Without this, `<C-V>` at column 2 then `k` over a one-character line
    // dragged the block's whole left edge to column 1, and the next `k` to column 0.
    val column = survivor?.vimLastColumn
    val anchor = survivor?.vimSelectionStart
    // Nearest to the active line rather than the last, because the active line is not always in the
    // block any more: a line too short to hold the slice drops out, and `<C-V>kk` over a one-
    // character line and then an empty one lands the motion on a line that has no caret. IdeaVim
    // hands the flag to the line above or below it, whichever the motion came from - `carets [9,
    // 12]`, the caret from the `b` line dragged up to the empty one - and picking the last would
    // have dragged the wrong end of the block instead.
    val primary = rebuilt.minByOrNull { (line, _) -> abs(line - activeLine) }!!.second
    for ((_, caret) in rebuilt) caret.isPrimary = caret === primary
    if (anchor != null) primary.vimSelectionStart = anchor
    // Every caret of the block, not only the primary. The remembered column is what the block's
    // edge is drawn at, and a caret built for a line it has never been on has none of its own - so
    // without this each new one answered with whatever column its line clamped to, and the block
    // widened by a column on every motion over ragged text. IdeaVim gets this for free by keeping
    // the column against the editor rather than against the caret.
    if (column != null) for ((_, caret) in rebuilt) caret.vimLastColumn = column

    val carets = rebuilt.map { it.second }
    val removed = vimCarets.filter { existing -> carets.none { it === existing } }
    vimCarets.clear()
    vimCarets += carets
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
  /**
   * Leaving Select mode, which is Visual mode with the keyboard behaving as a normal editor's.
   *
   * IdeaVim's version of this is mostly IntelliJ bookkeeping - it drops a pending visual-mode timer
   * and locks out its own selection listener while it clears the selections, because IntelliJ fires
   * a selection change back at it. Neither applies here: this host has no timer, and it recognises
   * its own selection events by comparing what arrives with what [flushCarets] pushed. What is left
   * is the part that is Vim's rather than any editor's, and that is what this does.
   *
   * [adjustCaret] steps a caret sitting on the end of a line back onto its last character, because
   * outside Insert and Visual mode Vim has nowhere to put a caret past the end.
   */
  override fun exitSelectModeNative(adjustCaret: Boolean) {
    if (mode !is Mode.SELECT) return
    mode = mode.returnTo
    for (caret in carets()) {
      caret.removeSelection()
      caret.vimSelectionStartClear()
      if (!adjustCaret || isEndAllowed) continue
      val line = offsetToBufferPosition(caret.offset).line
      val lineEnd = getLineEndOffset(line)
      if (caret.offset == lineEnd && caret.offset != getLineStartOffset(line)) {
        caret.moveToInlayAwareOffset(caret.offset - 1)
      }
    }
  }
  /** The last column on a line, which is its length while a visual line is a buffer line. */
  override fun getLastVisualLineColumnNumber(line: Int): Int =
    getLineEndOffset(line) - getLineStartOffset(line)
  override fun createIndentBySize(size: Int): String = indentConfig.createIndentBySize(size)
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
  override val indentConfig: VimIndentConfig = VsCodeIndentConfig(this)

  /**
   * Whether this editor's indent options have been started off at what VS Code says.
   *
   * Per editor, not per session: VS Code resolves indentation per file, per language and - with
   * `detectIndentation` on - from the file's own contents, so two windows genuinely have two
   * answers. See `seedIndent`.
   */
  internal var seededIndent: Boolean = false
  /**
   * What `R` overwrote, so that backspace in replace mode puts it back. Held per editor because
   * replace mode is per window; the engine builds and clears it.
   */
  /**
   * Vim's replace stack, and the markers that go with it.
   *
   * The setter is what makes this more than a field. A mask keeps one live marker per character it
   * has overwritten, and every marker in this buffer is moved on every edit - so a mask left behind
   * when replace mode ends is not only memory, it is work added to every keystroke afterwards, for
   * ever. `forgetAllReplaceMasks` sets this to null and is the moment the engine says they are done
   * with.
   */
  override var replaceMask: VimEditorReplaceMask? = null
    set(value) {
      if (value == null) field?.markers?.forEach { buffer.removeMarker(it) }
      field = value
    }
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

