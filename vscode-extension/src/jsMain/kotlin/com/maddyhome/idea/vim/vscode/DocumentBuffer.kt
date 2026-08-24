/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

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
  var text: String = editor.document.getText()
    private set

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
    if (editor.document.getText() == flushed) return false
    reseed()
    return true
  }

  /** Takes the document's current text, discarding anything not yet flushed. */
  fun reseed() {
    val before = text
    text = editor.document.getText()
    flushed = text
    revision++
    // The whole buffer was replaced by something the engine did not do, so every marker in it is
    // about text that may no longer be there.
    onChanged(0, before.length, text, before)
  }

  fun replace(start: Int, end: Int, newText: String) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val replaced = text.substring(from, to)
    text = text.substring(0, from) + newText + text.substring(to)
    revision++
    onChanged(from, to, newText, replaced)
  }

  fun insert(offset: Int, newText: String) {
    val at = offset.coerceIn(0, text.length)
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
  private val listeners: MutableList<ChangesListener> = mutableListOf()

  fun createMarker(start: Int, end: Int): LiveRange = TrackedRange(start, end).also { markers += it }

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
    if (document.getText() != flushed) {
      // Something else edited the document since the engine last agreed with it. Writing now would
      // revert that edit, so take the document's version and let the caller decide.
      reseed()
      onResult(false)
      return
    }

    val start = commonPrefixLength(flushed, target)
    val end = commonSuffixLength(flushed, target, start)
    val range = Range(
      document.positionAt(start),
      document.positionAt(flushed.length - end),
    )
    val replacement = target.substring(start, target.length - end)

    editor.edit { it.replace(range, replacement) }.then { applied ->
      if (applied) {
        flushed = target
      } else {
        // VS Code refuses an edit against a stale document version, among other reasons. The
        // document is the truth in that case.
        reseed()
      }
      onResult(applied)
    }
  }

  private fun commonPrefixLength(before: String, after: String): Int {
    val limit = minOf(before.length, after.length)
    var index = 0
    while (index < limit && before[index] == after[index]) index++
    return index
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
