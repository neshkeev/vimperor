/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

/**
 * A document and editor that behave the way VS Code's do, for tests.
 *
 * The behaviours that matter are the ones the engine has to be reconciled with: text is readable
 * synchronously, edits are not applied until the promise resolves, and every range in an `edit`
 * callback is resolved against the document as it was before the callback ran. A fake that applied
 * edits eagerly would let code pass here and fail in a real window.
 *
 * [recordedEdits] is what a test asserts on when the question is not *whether* the document ends up
 * right but *how* it got there - a whole-document rewrite and a one-character replacement both
 * produce the same text.
 */
class FakeDocument(text: String) : TextDocument {
  var content: String = text
    internal set

  override val uri: Uri = FakeUri("file", "/test/buffer.txt")
  override val fileName: String get() = uri.fsPath
  override val isUntitled: Boolean = false

  /** Set by a test that wants to model an editor with unsaved changes. */
  override var isDirty: Boolean = false
  override val lineCount: Int get() = content.count { it == '\n' } + 1

  /** VS Code bumps this on every applied edit, and refuses edits made against an older one. */
  override var version: Int = 1
    internal set

  // VS Code's `getText(range?)` is optional-argument, and Kotlin will not let an override restate
  // that default. The behaviour is the one VS Code documents: no range means the whole document.
  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun getText(range: Range?): String {
    if (range == null) return content
    return content.substring(offsetAt(range.start), offsetAt(range.end))
  }

  override fun offsetAt(position: Position): Int {
    var offset = 0
    repeat(position.line) {
      val newline = content.indexOf('\n', offset)
      if (newline < 0) return content.length
      offset = newline + 1
    }
    val lineEnd = content.indexOf('\n', offset).let { if (it < 0) content.length else it }
    return minOf(offset + position.character, lineEnd)
  }

  override fun positionAt(offset: Int): Position {
    val clamped = offset.coerceIn(0, content.length)
    val lineStart = content.lastIndexOf('\n', maxOf(clamped - 1, 0)).let {
      if (it < 0 || clamped == 0) 0 else it + 1
    }
    val line = content.substring(0, lineStart).count { it == '\n' }
    return Position(line, clamped - lineStart)
  }
}

private class FakeUri(override val scheme: String, override val path: String) : Uri {
  override val fsPath: String get() = path
}

/** A single replacement, in the offsets it was made against, so a test can see how wide it was. */
data class RecordedEdit(val start: Int, val end: Int, val text: String)

class FakeEditor(text: String) : TextEditor {
  override val document: FakeDocument = FakeDocument(text)
  override var selection: Selection = FakeSelection(Position(0, 0), Position(0, 0))
  override var selections: Array<Selection> = arrayOf(selection)

  val recordedEdits: MutableList<RecordedEdit> = mutableListOf()

  /** What the editor was asked to scroll to, so a test can see that it was asked at all. */
  val revealedRanges: MutableList<Range> = mutableListOf()

  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun revealRange(range: Range, revealType: Int) {
    revealedRanges += range
  }

  /** What each decoration type currently paints, which is what VS Code's replace-not-add model is. */
  val decorations: MutableMap<TextEditorDecorationType, List<Range>> = mutableMapOf()

  override fun setDecorations(decorationType: TextEditorDecorationType, ranges: Array<Range>) {
    decorations[decorationType] = ranges.toList()
  }

  /** Set to refuse the next edit, the way VS Code does when the document has moved on. */
  var refuseEdits: Boolean = false

  /** Previous contents, so the fake can undo the way VS Code's `undo` command does. */
  private val history: MutableList<String> = mutableListOf()

  /** VS Code's undo, as a command would perform it: the document changes, nothing is reported. */
  fun undo() {
    val previous = history.removeLastOrNull() ?: return
    document.content = previous
    document.version++
  }

  override fun edit(callback: (TextEditorEdit) -> Unit): Thenable<Boolean> {
    val builder = FakeEditBuilder(document)
    callback(builder)
    if (refuseEdits) return resolved(false)

    // Applied back to front so that earlier offsets are still valid as later edits land - which is
    // what VS Code's "resolved against the pre-edit document" guarantee amounts to.
    val edits = builder.edits.sortedByDescending { it.start }
    if (edits.isNotEmpty()) history += document.content
    for (edit in edits) {
      document.content = document.content.substring(0, edit.start) + edit.text +
        document.content.substring(edit.end)
    }
    if (edits.isNotEmpty()) document.version++
    recordedEdits += builder.edits
    return resolved(true)
  }
}

class FakeSelection(override val anchor: Position, override val active: Position) : Selection

private class FakeEditBuilder(private val document: FakeDocument) : TextEditorEdit {
  val edits: MutableList<RecordedEdit> = mutableListOf()

  override fun replace(location: Range, value: String) {
    edits += RecordedEdit(document.offsetAt(location.start), document.offsetAt(location.end), value)
  }

  override fun insert(location: Position, value: String) {
    val offset = document.offsetAt(location)
    edits += RecordedEdit(offset, offset, value)
  }

  override fun delete(location: Range) {
    edits += RecordedEdit(document.offsetAt(location.start), document.offsetAt(location.end), "")
  }
}

/**
 * A [Thenable] that has already completed. VS Code's is a real promise, so a caller must not assume
 * its callback ran before `edit` returned - but a test that had to wait for a microtask could not
 * assert synchronously, and nothing in this code path defers.
 */
private fun <T> resolved(value: T): Thenable<T> = object : Thenable<T> {
  override fun then(onFulfilled: (T) -> Unit): Thenable<T> {
    onFulfilled(value)
    return this
  }
}
