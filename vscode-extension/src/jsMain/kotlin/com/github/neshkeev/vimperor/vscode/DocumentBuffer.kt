/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.common.ChangesListener
import com.maddyhome.idea.vim.common.LiveRange

/**
 * The engine's synchronous view of an asynchronous document.
 *
 * This is the one place where VS Code and the engine genuinely disagree. The engine mutates a
 * buffer and reads it back on the next line - `insertText`, `replaceString` and `addLine` are the
 * whole of its write surface, and every one of them is expected to have happened by the time it
 * returns. VS Code's `TextEditor.edit` returns a promise, and the document does not change until it
 * resolves.
 *
 * So the engine writes here, into a string, and VS Code is caught up afterwards. Reads need no such
 * treatment: `TextDocument` is synchronously readable, which is why this works at all.
 *
 * The cost is that between [reseed] and [flush] there are two versions of the buffer, and anything
 * else that edits the document in that window - the user, another extension, a language server -
 * is editing the one the engine is not looking at. [flush] refuses to write over a document that
 * moved underneath it rather than silently reverting whatever did the moving.
 */
class DocumentBuffer(private val editor: TextEditor) {

  /** What the engine sees. Diverges from the document only between a mutation and its [flush]. */
  var text: String = normalized()
    private set

  /**
   * The document's text with `\r\n` collapsed to `\n`, which is the only kind the engine knows.
   *
   * IntelliJ's `Document` is always `\n` and the file's separator is applied on the way to disk, so
   * every offset in `vim-engine` is a normalised one and nothing in it has ever seen a carriage
   * return. VS Code hands the text over exactly as the file has it. Left alone, a CRLF file puts a
   * `\r` at the end of every line *inside* the line as the engine measures it: `$` lands on it, `x`
   * deletes it, `A` appends after it, and `J` leaves one in the middle of the joined line. None of
   * the tests here would have noticed, because every one of them writes `\n`.
   *
   * A lone `\r` is deliberately left as an ordinary character. VS Code's own line model is the
   * thing positions are mapped against and it has exactly two line endings; guessing at what it
   * does with a stray carriage return would be a worse answer than treating it as text.
   */
  private fun normalized(): String {
    val raw = editor.document.getText()
    return if (raw.indexOf('\r') < 0) raw else raw.replace("\r\n", "\n")
  }

  /** The inverse, for text on its way back to a document that wants `\r\n`. */
  private fun withDocumentLineEndings(value: String): String =
    if (editor.document.eol == EndOfLine.CRLF) value.replace("\n", "\r\n") else value

  /** The document as it was when this buffer last agreed with it, and what [flush] diffs against. */
  private var flushed: String = text

  /**
   * Bumped whenever [text] changes, so anything derived from it - line offsets, most of all - can
   * tell that its cache is stale without comparing the whole buffer.
   */
  var revision: Int = 0
    private set

  /**
   * Whether the document has been changed by something other than the engine, and takes its version
   * if so.
   *
   * Reads during a command go to the buffer, so a document that moved between commands would be
   * read as if it had not - the engine would compute offsets against text that is no longer there.
   * Checked before a key is handled rather than subscribed to, because a change event says a change
   * happened, not whether the engine caused it.
   */
  fun syncIfDocumentMoved(): Boolean {
    if (normalized() == flushed) return false
    reseed()
    return true
  }

  /** Takes the document's current text, discarding anything not yet flushed. */
  fun reseed() {
    val before = text
    text = normalized()
    flushed = text
    revision++
    // The whole buffer was replaced by something the engine did not do, so every marker in it is
    // about text that may no longer be there.
    onChanged(0, before.length, text, before)
  }

  /**
   * Called with a change before the text moves, which is the whole of what `U` needs.
   *
   * Vim's `U` keeps one pristine copy of one line, saved the first time that line is touched, and
   * "before" is not an optimisation there - once the edit has been applied the original is gone.
   * IntelliJ drives the same call from a document listener; here every mutation already funnels
   * through two methods, so this is exact rather than reconstructed.
   */
  var beforeChange: (start: Int, end: Int, newText: String) -> Unit = { _, _, _ -> }

  fun replace(start: Int, end: Int, newText: String) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    beforeChange(from, to, newText)
    val replaced = text.substring(from, to)
    text = text.substring(0, from) + newText + text.substring(to)
    revision++
    onChanged(from, to, newText, replaced)
  }

  fun insert(offset: Int, newText: String) {
    val at = offset.coerceIn(0, text.length)
    beforeChange(at, at, newText)
    text = text.substring(0, at) + newText + text.substring(at)
    revision++
    onChanged(at, at, newText, "")
  }

  // ---- Everything that has to move when the text does.
  //
  // This is what IntelliJ's range markers are, and the engine needs them before anything else does:
  // insert mode records where the insertion began and asks later how much was typed, which is only
  // answerable if the mark moved with the text. Every mutation goes through this class, so tracking
  // them here is exact - unlike deriving them from VS Code's change events, which arrive after the
  // fact and describe a document the engine has already moved past.

  private val markers: MutableList<TrackedRange> = mutableListOf()

  /**
   * How many markers are being kept alive, which a test can assert does not grow without bound.
   *
   * Every one of these is moved on every edit, so a marker nobody will read again is not merely
   * memory - it is work added to every keystroke for the rest of the session.
   */
  val markerCount: Int get() = markers.size
  private val listeners: MutableList<ChangesListener> = mutableListOf()

  fun createMarker(start: Int, end: Int): LiveRange = TrackedRange(start, end).also { markers += it }

  /**
   * Stops tracking a marker.
   *
   * By identity rather than by equality: [TrackedRange] compares by offsets, so two markers over
   * the same span are equal, and removing "the one that equals this" could take somebody else's.
   */
  fun removeMarker(marker: LiveRange) {
    markers.removeAll { it === marker }
  }

  fun addChangeListener(listener: ChangesListener) {
    listeners += listener
  }

  fun removeChangeListener(listener: ChangesListener) {
    listeners.remove(listener)
  }

  private fun onChanged(start: Int, end: Int, newText: String, replaced: String) {
    val delta = newText.length - (end - start)
    for (marker in markers) marker.adjust(start, end, delta)
    if (listeners.isNotEmpty()) {
      val change = ChangesListener.Change(replaced, newText, start)
      listeners.toList().forEach { it.documentChanged(change) }
    }
  }

  /**
   * An offset pair that follows edits.
   *
   * An offset before the change does not move; one after it shifts by the change's size. An offset
   * *inside* what was replaced has nowhere of its own to be, so it collapses to the start - the
   * same choice IntelliJ makes, and the reason a marker can end up empty rather than wrong.
   */
  private class TrackedRange(start: Int, end: Int) : LiveRange {
    override var startOffset: Int = start
      private set
    override var endOffset: Int = end
      private set

    fun adjust(changeStart: Int, changeEnd: Int, delta: Int) {
      startOffset = adjustOffset(startOffset, changeStart, changeEnd, delta)
      endOffset = adjustOffset(endOffset, changeStart, changeEnd, delta).coerceAtLeast(startOffset)
    }

    private fun adjustOffset(offset: Int, changeStart: Int, changeEnd: Int, delta: Int): Int = when {
      offset <= changeStart -> offset
      offset >= changeEnd -> offset + delta
      else -> changeStart
    }

    /**
     * Two markers over the same span are the same marker, which is not obvious and is load-bearing.
     *
     * Vim's replace stack is a map keyed by a marker, and it looks an entry up by *building a new
     * marker* at the offset it wants. With identity equality that lookup never matches anything, so
     * backspace in replace mode silently restored nothing. IntelliJ's `IjLiveRange` compares by
     * offsets for exactly this reason; this had not, and nothing noticed because nothing in this
     * module had pressed `R` and then backspace.
     */
    override fun equals(other: Any?): Boolean =
      other is TrackedRange && other.startOffset == startOffset && other.endOffset == endOffset

    override fun hashCode(): Int = 31 * startOffset + endOffset
  }

  /**
   * Writes everything the engine changed to the document, as a single edit over the smallest range
   * that covers it.
   *
   * One edit rather than a replayed list of operations: VS Code resolves every range in an `edit`
   * callback against the document as it was before the callback ran, so operations that build on
   * each other - which Vim's are, constantly - cannot be handed over as they happened. Replacing
   * the whole document instead would be simpler still and is what a first attempt usually does, but
   * it rewrites every line, which costs the undo stack, decorations, and folding on a file where
   * `x` deleted one character.
   *
   * Trimming to the common prefix and suffix keeps the range tight for the edits Vim actually
   * makes. It is not minimal for every case - two carets deleting on distant lines produce one
   * range spanning both - and that is the known limit of doing this in one edit.
   *
   * Reports through [onResult]: `true` if the document now matches, `false` if VS Code rejected the
   * edit or the document had moved. Nothing is written when nothing changed, so a motion does not
   * mark the file dirty.
   */
  fun flush(onResult: (Boolean) -> Unit = {}) {
    val target = text
    if (target == flushed) {
      onResult(true)
      return
    }

    val document = editor.document
    if (normalized() != flushed) {
      // Something else edited the document since the engine last agreed with it. Writing now would
      // revert that edit, so take the document's version and let the caller decide.
      reseed()
      onResult(false)
      return
    }

    // Line and column rather than `document.positionAt`, which takes a *document* offset - and on a
    // CRLF file the two disagree by one character for every line before the change. A position does
    // not disagree: VS Code's character index is within the line's own text, which excludes the
    // separator whichever it is, so line-and-column is the currency both sides read the same way.
    // The walk is free: finding the common prefix already visits every character before the change.
    var start = 0
    var line = 0
    var lineStart = 0
    val shared = minOf(flushed.length, target.length)
    while (start < shared && flushed[start] == target[start]) {
      if (flushed[start] == '\n') {
        line++
        lineStart = start + 1
      }
      start++
    }
    val startPosition = Position(line, start - lineStart)

    val end = flushed.length - commonSuffixLength(flushed, target, start)
    var index = start
    while (index < end) {
      if (flushed[index] == '\n') {
        line++
        lineStart = index + 1
      }
      index++
    }
    val range = Range(startPosition, Position(line, end - lineStart))
    val replacement = withDocumentLineEndings(target.substring(start, target.length - (flushed.length - end)))

    editor.edit { it.replace(range, replacement) }.then({ applied ->
      if (applied) {
        flushed = target
      } else {
        // VS Code refuses an edit against a stale document version, among other reasons. The
        // document is the truth in that case.
        reseed()
      }
      onResult(applied)
    })
  }

  /** Length of the shared suffix, stopping before [prefix] so the two never overlap. */
  private fun commonSuffixLength(before: String, after: String, prefix: Int): Int {
    val limit = minOf(before.length, after.length) - prefix
    var index = 0
    while (
      index < limit &&
      before[before.length - 1 - index] == after[after.length - 1 - index]
    ) {
      index++
    }
    return index
  }
}
