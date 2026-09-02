/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

/**
 * An editor over nothing, which is what `injector.fallbackWindow` is.
 *
 * The reason recorded for leaving this out was "the editor the engine reaches for when there is no
 * window at all", which made it sound like a corner nobody reaches. It is not. Every scope in the
 * thin API resolves its editor as `projectId?.let { getSelectedEditor(it) } ?: injector
 * .fallbackWindow`, and an extension's `init` runs with a null project id by construction - there
 * is no editor yet when a plugin is being set up. So this is not the last resort; for a plugin
 * declaring its mappings it is the *first* thing asked for, and without it the whole extension
 * chain stops at the first line.
 *
 * The option group needs it for a second reason worth naming: options local to a window are stored
 * against an editor, and the global values of window-local options have to live somewhere when no
 * window is open. IntelliJ's host keeps a hidden editor over an empty document for exactly this.
 *
 * Nothing here is on screen, so everything that would be visible does nothing: an edit is applied
 * to the string and reported as applied, scrolling is not recorded, and decorations are dropped.
 * The one thing that has to be real is the text, because that is what the engine reads.
 */
internal class DetachedTextEditor : TextEditor {

  override val document: TextDocument = DetachedDocument()

  override var selection: Selection = DetachedSelection(Position(0, 0), Position(0, 0))
  override var selections: Array<Selection> = arrayOf(selection)

  override fun edit(callback: (TextEditorEdit) -> Unit): Thenable<Boolean> {
    val document = document as DetachedDocument
    val edits = mutableListOf<Triple<Int, Int, String>>()
    callback(
      object : TextEditorEdit {
        override fun replace(location: Range, value: String) {
          edits += Triple(document.offsetAt(location.start), document.offsetAt(location.end), value)
        }

        override fun insert(location: Position, value: String) {
          val at = document.offsetAt(location)
          edits += Triple(at, at, value)
        }

        override fun delete(location: Range) {
          edits += Triple(document.offsetAt(location.start), document.offsetAt(location.end), "")
        }
      },
    )
    // Back to front, so that the offsets of the earlier edits are still the ones they were made
    // against - which is VS Code's guarantee, and the reason `DocumentBuffer` sends only one.
    for ((start, end, text) in edits.sortedByDescending { it.first }) {
      document.text = document.text.substring(0, start) + text + document.text.substring(end)
    }
    return Resolved(true)
  }

  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun revealRange(range: Range, revealType: Int) {}

  /** One line, which is what an empty buffer shows. Never empty, so nothing has to index carefully. */
  override val visibleRanges: Array<Range> = arrayOf(Range(Position(0, 0), Position(0, 0)))

  override val options: TextEditorOptions = object : TextEditorOptions {
    override val tabSize: dynamic get() = 4
    override val insertSpaces: dynamic get() = true
  }

  override fun setDecorations(decorationType: TextEditorDecorationType, ranges: Array<Range>) {}
}

private class DetachedSelection(
  override val anchor: Position,
  override val active: Position,
) : Selection

/**
 * A document with no file behind it.
 *
 * The `untitled:` scheme with no path is what VS Code calls a buffer that has never been anywhere,
 * and giving this one a URI of its own keeps it from colliding with a real editor in the host's map
 * of open editors.
 */
private class DetachedDocument : TextDocument {
  var text: String = ""

  override val uri: Uri = object : Uri {
    override val scheme: String = "untitled"
    override val path: String = "/ideavim-fallback"
    override val fsPath: String get() = path
  }

  override val fileName: String get() = uri.path
  override val isUntitled: Boolean = true
  override val isDirty: Boolean = false
  override val eol: Int = EndOfLine.LF
  override val lineCount: Int get() = text.count { it == '\n' } + 1
  override val version: Int = 1

  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun getText(range: Range?): String {
    if (range == null) return text
    return text.substring(offsetAt(range.start), offsetAt(range.end))
  }

  override fun offsetAt(position: Position): Int {
    var offset = 0
    repeat(position.line) {
      val newline = text.indexOf('\n', offset)
      if (newline < 0) return text.length
      offset = newline + 1
    }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    return minOf(offset + position.character, lineEnd)
  }

  override fun positionAt(offset: Int): Position {
    val clamped = offset.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', maxOf(clamped - 1, 0)).let {
      if (it < 0 || clamped == 0) 0 else it + 1
    }
    return Position(text.substring(0, lineStart).count { it == '\n' }, clamped - lineStart)
  }
}

/** A promise that has already happened, since nothing here is asynchronous. */
private class Resolved<T>(private val value: T) : Thenable<T> {
  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun then(onFulfilled: (T) -> Unit, onRejected: (Any?) -> Unit): Thenable<T> {
    onFulfilled(value)
    return this
  }
}
