/*
 * Copyright 2026 Nikita Eshkeev
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
class FakeDocument(text: String, path: String = "/test/buffer.txt") : TextDocument {
  var content: String = text
    internal set

  /**
   * Which file this is, which every fake used to answer identically.
   *
   * The host keys its editors by document identity - `scheme://path` - so two fakes claiming the
   * same path were one buffer as far as it was concerned, and a test that opened a second file was
   * really replacing the first. That hid a real bug: a replaced editor was inheriting its
   * window-local options from the fallback window rather than from the editor it replaced.
   */
  override val uri: Uri = FakeUri("file", path)
  override val fileName: String get() = uri.fsPath
  override val isUntitled: Boolean = false

  /** Set by a test that wants to model an editor with unsaved changes. */
  override var isDirty: Boolean = false

  /**
   * How this file ends its lines, which a test sets alongside CRLF text.
   *
   * VS Code hands the text back exactly as the file has it, so a fake that reported CRLF while
   * holding `\n` would be the one thing this cannot be used to check.
   */
  override var eol: Int = EndOfLine.LF
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

/** One `revealRange` request: the lines it named and the type it was asked with. */
data class RecordedReveal(val start: Int, val end: Int, val type: Int)

/** A single replacement, in the offsets it was made against, so a test can see how wide it was. */
data class RecordedEdit(val start: Int, val end: Int, val text: String)

class FakeEditor(text: String, path: String = "/test/buffer.txt") : TextEditor {
  override val document: FakeDocument = FakeDocument(text, path)
  override var selections: Array<Selection> = arrayOf(Selection(Position(0, 0), Position(0, 0)))

  /**
   * The primary selection, which VS Code documents as "shorthand for `TextEditor.selections[0]`" -
   * in both directions. Its setter is `this._selections = [value]`, so assigning it *discards* every
   * other caret.
   *
   * A plain field here for a long time, which is why a real window was the first thing to notice
   * that `flushCarets` pushed a whole block selection and then threw all but one line of it away on
   * the very next line. A fake that is more forgiving than the real thing is a fake that hides bugs.
   */
  override var selection: Selection
    get() = selections.first()
    set(value) {
      selections = arrayOf(value)
    }

  val recordedEdits: MutableList<RecordedEdit> = mutableListOf()

  /** What the editor was asked to scroll to, so a test can see that it was asked at all. */
  val revealedRanges: MutableList<Range> = mutableListOf()

  /** The same reveals, as the line asked for and the type it was asked with. */
  val reveals: MutableList<RecordedReveal> = mutableListOf()

  /**
   * The top line each `editorScroll` was asked to put on screen, in order.
   *
   * What a scroll command actually *does* is ask; whether the view then moves is the editor's
   * business, and in a real window it may not report that it did. So this is the observable that
   * survives a viewport which never catches up, and the one a test can assert on when modelling
   * that window.
   */
  val scrolls: MutableList<Int> = mutableListOf()

  /**
   * A viewport, because scrolling cannot be tested without one.
   *
   * VS Code gives an extension half a conversation: `revealRange` moves the view and
   * `visibleRanges` says where it ended up. A fake that recorded the reveal and left
   * `visibleRanges` alone would let every scroll command pass while doing nothing, since each one
   * reads the view back to work out where to go next. So this models the view the way the editor
   * does - a window [viewportHeight] lines tall starting at [topLine], moved by a reveal according
   * to the type it was given, and never scrolled above the first line.
   *
   * Folding is not modelled: `visibleRanges` is always one range. This host cannot fold anyway.
   */
  var viewportHeight: Int = 10
  var topLine: Int = 0

  /**
   * Whether a scroll moves the reported view at once.
   *
   * VS Code's does not. A scroll is scheduled and `visibleRanges` goes on describing the old view
   * until the editor paints, so a command that scrolls and then reads the view back gets the answer
   * it had before. Applying it immediately, as this fake did unconditionally, is a convenient lie -
   * and it is the one that let `<C-E>` and `<C-Y>` pass every test here while doing nothing at all
   * in a real window.
   *
   * Set this false to model the real editor; [paint] then applies what was scheduled, the way a
   * rendered frame would.
   */
  var revealsTakeEffectImmediately: Boolean = true
  private var pendingTopLine: Int? = null

  init {
    // `editorScroll` goes to whatever editor has focus, and there is nothing on the editor object
    // to intercept - so this makes itself the focused one. See the stub's `commands.handlers`.
    val handlers = js("require('vscode').commands.handlers")
    handlers["editorScroll"] = { args: dynamic -> editorScroll(args) }
  }

  /**
   * VS Code's `editorScroll`, in the one shape this host asks for it: by whole lines, without
   * dragging the caret. Clamped to the document, the way an editor with `scrollBeyondLastLine`
   * turned off would clamp - which is the drift the host's belief has to survive.
   */
  private fun editorScroll(args: dynamic) {
    val value = args.value as Int
    val signed = if (args.to as String == "up") -value else value
    val newTop = (scrollTop + signed).coerceIn(0, lastLine)
    scrolls += newTop
    if (revealsTakeEffectImmediately) topLine = newTop else pendingTopLine = newTop
  }

  /**
   * Where the editor has scrolled to, painted or not.
   *
   * The lag is in `visibleRanges`, which is the snapshot an *extension* reads; the editor widget
   * itself moves when it is asked. So a second scroll in the same tick computes from where the
   * first one sent it, and only a reader outside the editor sees the old view. Modelling it the
   * other way makes a following `scrollCaretIntoView` undo the scroll that was just requested,
   * which is not what a real window does.
   */
  private val scrollTop: Int get() = pendingTopLine ?: topLine

  /** Applies a reveal that was deferred, as painting a frame would. */
  fun paint() {
    pendingTopLine?.let { topLine = it }
    pendingTopLine = null
  }

  /**
   * How this file is indented, which VS Code resolves and an extension only reads.
   *
   * Defaulted to VS Code's own defaults so that every other test keeps the behaviour it asserted
   * before there was an answer here at all.
   */
  var indentWithSpaces: Boolean = true
  var indentWidth: Int = 4

  /** The caret's shape, which a Vim emulator writes and a test reads back. */
  var cursorStyle: Int = TextEditorCursorStyle.Line

  /** The gutter, which `'number'` and `'relativenumber'` write. */
  var lineNumbers: Int = TextEditorLineNumbersStyle.On

  override val options: TextEditorOptions
    get() = object : TextEditorOptions {
      override val tabSize: dynamic get() = indentWidth
      override val insertSpaces: dynamic get() = indentWithSpaces

      // Through the editor's own field, because VS Code's `options` is a live view onto the editor
      // rather than a snapshot - writing `editor.options.cursorStyle` changes the editor.
      override var cursorStyle: Int
        get() = this@FakeEditor.cursorStyle
        set(value) {
          this@FakeEditor.cursorStyle = value
        }

      override var lineNumbers: Int
        get() = this@FakeEditor.lineNumbers
        set(value) {
          this@FakeEditor.lineNumbers = value
        }
    }

  private val lastLine: Int get() = document.lineCount - 1

  override val visibleRanges: Array<Range>
    get() {
      val top = topLine.coerceIn(0, lastLine)
      val bottom = (top + viewportHeight - 1).coerceIn(0, lastLine)
      return arrayOf(Range(Position(top, 0), Position(bottom, 0)))
    }

  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun revealRange(range: Range, revealType: Int) {
    revealedRanges += range
    reveals += RecordedReveal(range.start.line, range.end.line, revealType)
    val start = range.start.line
    val end = range.end.line
    val current = scrollTop
    val newTop = when (revealType) {
      TextEditorRevealType.AtTop -> start
      TextEditorRevealType.InCenter -> start - (viewportHeight - 1) / 2
      TextEditorRevealType.InCenterIfOutsideViewport ->
        if (start < current || end > current + viewportHeight - 1) start - (viewportHeight - 1) / 2 else current
      // Default: the smallest scroll that brings the range on screen.
      else -> when {
        start < current -> start
        end > current + viewportHeight - 1 -> end - viewportHeight + 1
        else -> current
      }
    }.coerceIn(0, lastLine)
    if (revealsTakeEffectImmediately) topLine = newTop else pendingTopLine = newTop
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
  @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
  override fun then(onFulfilled: (T) -> Unit, onRejected: (Any?) -> Unit): Thenable<T> {
    onFulfilled(value)
    return this
  }
}
