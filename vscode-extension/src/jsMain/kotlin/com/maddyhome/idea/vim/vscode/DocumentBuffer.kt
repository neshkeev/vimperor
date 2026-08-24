/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

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

  /** Takes the document's current text, discarding anything not yet flushed. */
  fun reseed() {
    text = editor.document.getText()
    flushed = text
    revision++
  }

  fun replace(start: Int, end: Int, newText: String) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    text = text.substring(0, from) + newText + text.substring(to)
    revision++
  }

  fun insert(offset: Int, newText: String) {
    val at = offset.coerceIn(0, text.length)
    text = text.substring(0, at) + newText + text.substring(at)
    revision++
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
