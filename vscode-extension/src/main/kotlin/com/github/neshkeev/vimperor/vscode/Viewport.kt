/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimScrollGroup
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.normalizeLine
import com.maddyhome.idea.vim.api.options
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the view is, and how to move it.
 *
 * Vim and VS Code disagree about what scrolling is. In Vim the view is the thing you move -
 * `<C-E>` moves it down a line, `zt` puts the current line at the top - and the caret comes along
 * only if it would otherwise fall off. `TextEditor.revealRange`, the API an extension is pointed
 * at, says where a *range* should end up instead, and leaves the rest to the editor.
 *
 * That difference is not academic. A real window put every `AtTop` reveal five lines above the line
 * it named, consistently, over sixty of them: `zt` on line 18 left the view at 13, and `<C-E>` -
 * which asks for one line further down than it believes it is - walked the view four lines
 * *backwards* per press. Whatever the five lines are (sticky scroll, a surrounding-lines setting,
 * an editor padding), a reveal is a request to be interpreted and this one was interpreted.
 *
 * So nothing here reveals any more. [VsCodeEditor.scrollViewTo] is the single primitive, and it is
 * `editorScroll` - VS Code's own "move the view by N lines", which has no range to reason about
 * and nothing to be five lines out by. Every vertical scroll in Vim reduces to it, and the
 * arithmetic that decides N is Vim's own, in line numbers, here.
 *
 * Columns do not. `visibleRanges` is line ranges only - there is no horizontal viewport to read and
 * `editorScroll` moves only up and down - so `zh`, `zl`, `zs`, `ze`, `zH` and `zL` are the group that
 * cannot be written rather than merely not written yet. They report failure, which is what Vim does
 * when a scroll has nowhere to go.
 */

/** What VS Code says the window shows, which is where it has been painted rather than where it is. */
internal val VsCodeEditor.reportedTopLine: Int
  get() = nativeEditor.visibleRanges.firstOrNull()?.start?.line ?: 0

internal val VsCodeEditor.reportedBottomLine: Int
  get() = nativeEditor.visibleRanges.lastOrNull()?.end?.line ?: (lineCount() - 1)

/**
 * The first line on screen: Vim's idea of it, which is the one that has to be built on.
 *
 * A scroll command is a statement about where the window goes next, and the next command continues
 * from there. Asking VS Code where the view is instead makes every command start from wherever the
 * editor has got round to painting - which, measured in a real window, is where it started. So the
 * top line asked for is remembered, and kept until the editor reports a different one from the last
 * it reported, which is it saying where the view really is.
 *
 * See [VsCodeEditor.believedTopLine] for what that cost.
 */
/**
 * Whether what VS Code last said about the viewport still describes the document that is there.
 *
 * Cleared the moment the editor reports anything different, which is it having repainted. See
 * [VsCodeEditor.viewportReportedBeforeEdit] for what goes wrong while it has not.
 */
internal val VsCodeEditor.viewportIsStale: Boolean
  get() {
    val before = viewportReportedBeforeEdit ?: return false
    if ((reportedTopLine to reportedBottomLine) != before) {
      viewportReportedBeforeEdit = null
      return false
    }
    return true
  }

/**
 * Called when a host command has rewritten the document behind the engine's back.
 *
 * Two things stop being true at once: where the view is, and how tall it looked. Both are dropped,
 * and the caret is handed to VS Code to reveal - it is the only party that knows the real height,
 * and with a document that now fits on screen its answer is to not scroll at all, which is the
 * right one and the one this host could not have worked out.
 */
internal fun VsCodeEditor.viewportChangedUnderneath() {
  viewportReportedBeforeEdit = reportedTopLine to reportedBottomLine
  believedTopLine = null
  lastReportedTopLine = null
  revealPrimaryCaret()
}

internal val VsCodeEditor.screenTopLine: Int
  get() {
    val reported = reportedTopLine
    if (reported != lastReportedTopLine) {
      // The editor has moved of its own accord - the user scrolled, or it caught up with a scroll.
      lastReportedTopLine = reported
      believedTopLine = null
      return reported
    }
    return believedTopLine ?: reported
  }

/**
 * How many lines the window shows - or as many as can be known.
 *
 * VS Code does not say how tall a window is. `visibleRanges` says which lines it painted, and that
 * is the height only when the range ends before the last line of the file. A range that reaches the
 * last line is clipped to the text: a five-line file in a ten-line window reports `0..4`, and after
 * `zt` on its last line - VS Code lets the view scroll past the end - it reports `4..4`.
 *
 * This used to say the reported ranges could be trusted for the height because a height changes
 * when the window is laid out and not when it scrolls. Of the window that is true. Of the report it
 * is not, and `zb` then put line 4 at the bottom of a one-line window, which is where it already was,
 * so it did nothing.
 *
 * So a clipped report is only a floor under the height: it can raise what is remembered, never lower
 * it. So is a stale one, which describes the document from before an edit and so cannot say whether
 * it was clipped. A fresh report that ends before the last line *is* the height, and replaces what
 * was remembered - which is how a window made shorter by opening a panel is believed. The cost runs
 * the other way: a window shortened while scrolled past the end keeps its old height until VS Code
 * next reports a range that is not clipped.
 *
 * At least one, so that arithmetic dividing by it is safe.
 */
internal val VsCodeEditor.screenHeight: Int
  get() {
    val reported = max(1, reportedBottomLine - reportedTopLine + 1)
    val onlyAFloor = reportedBottomLine >= lineCount() - 1 || viewportIsStale
    val height = if (onlyAFloor) max(reported, knownScreenHeight ?: 0) else reported
    knownScreenHeight = height
    return height
  }

/**
 * The last line on screen, derived from the top and the height.
 *
 * Not read from the ranges: the top no longer comes from them either, and a bottom that did would
 * describe a different window from the top.
 */
internal val VsCodeEditor.screenBottomLine: Int
  get() = min(screenTopLine + screenHeight - 1, max(0, lineCount() - 1))

/**
 * Moves the window so that [line] is its first line, as far as the end of the file allows.
 *
 * The one thing in this file that moves the view, and the reason it takes a line number rather than
 * a delta is that every Vim scroll is easier to say that way: `zt` names a line, `<C-E>` names one
 * more than the top, a page names a window's worth further on. The delta this sends is worked out
 * against [screenTopLine] - Vim's belief - so a run of presses composes even while VS Code has not
 * repainted once.
 *
 * A previous version of this asked `revealRange` for the line `AtTop`, which is the API written for
 * exactly this and which a real window answered five lines out, every time. `editorScroll` is not a
 * request about a range: it moves the scroll position by the number of lines it is given, and the
 * only thing that can move it somewhere else is the end of the document.
 *
 * That is also the one drift left. With `editor.scrollBeyondLastLine` turned off VS Code will
 * refuse to put the last line at the top and this will believe it did - until the editor reports a
 * top that contradicts it, which [screenTopLine] adopts.
 */
internal fun VsCodeEditor.scrollViewTo(line: Int) {
  val lastLine = max(0, lineCount() - 1)
  val target = line.coerceIn(0, lastLine)
  val from = screenTopLine
  val delta = target - from
  if (delta == 0) return
  logScroll(from, target)
  commands.executeCommand(
    VsCodeCommands.EDITOR_SCROLL,
    json(
      "to" to if (delta > 0) "down" else "up",
      "by" to "line",
      "value" to abs(delta),
      // Vim decides where the caret goes; the callers below do it explicitly, and letting VS Code
      // drag it too would fight them.
      "revealCursor" to false,
    ),
  )
  believedTopLine = target
  lastReportedTopLine = reportedTopLine
}

/**
 * Records a scroll for the trace: where the view was believed to be, where it was sent, and what VS
 * Code was reporting at the time.
 */
private fun VsCodeEditor.logScroll(from: Int, to: Int) {
  if (scrollLog.size < 12) scrollLog += "$from->$to(saw ${reportedTopLine}..${reportedBottomLine})"
}

/**
 * Brings [line] onto the screen if it is not already, and leaves the view alone if it is.
 *
 * The "if it is not already" is Vim's behaviour for a search: typing a pattern does not scroll the
 * window while the match you are heading for is already visible.
 */
internal fun VsCodeEditor.scrollLineIntoView(line: Int) {
  val target = line.coerceIn(0, max(0, lineCount() - 1))
  val top = screenTopLine
  val height = screenHeight
  if (target >= top && target <= top + height - 1) return
  scrollLineToMiddle(target)
}

/** Scrolls so that [line] is the last line on screen. */
internal fun VsCodeEditor.scrollLineToBottom(line: Int) = scrollViewTo(line - screenHeight + 1)

/** Scrolls so that [line] is in the middle, which is where Vim's `zz` puts it. */
internal fun VsCodeEditor.scrollLineToMiddle(line: Int) = scrollViewTo(line - (screenHeight - 1) / 2)

/** `'scrolloff'`, capped so that a large value on a small window cannot pin the caret off screen. */
private fun VsCodeEditor.scrollOffset(): Int =
  min(injector.options(this).scrolloff, (screenHeight - 1) / 2)

/**
 * Vim's rule that the caret stays on screen, with `'scrolloff'` lines to spare.
 *
 * The scroll commands move the view and drag the caret after it; this is the dragging. It returns
 * the line the caret should be on, which is its own line whenever that is already a comfortable
 * place to be.
 *
 * [newTop] is passed in rather than read, and that is the whole point of this function's shape.
 * A scroll is asynchronous: VS Code moves the view on a later frame, so `visibleRanges` goes on
 * describing where the view *was* until then. Anything that scrolls and then asks where the view is
 * gets the old answer, in a real window, every time. So the caller works out where the view is
 * going and everything downstream is told rather than asking.
 */
private fun VsCodeEditor.caretLineForView(newTop: Int, height: Int): Int {
  val lastLine = max(0, lineCount() - 1)
  val newBottom = min(newTop + height - 1, lastLine)
  val offset = scrollOffset()
  val top = if (newTop > 0) newTop + offset else newTop
  val bottom = if (newBottom < lastLine) newBottom - offset else newBottom
  val line = primaryCaret().getBufferPosition().line
  return line.coerceIn(min(top, bottom), max(top, bottom))
}

/** Moves the caret to [line], keeping its column or going to the first non-blank per `'startofline'`. */
private fun VsCodeEditor.moveCaretToLine(line: Int) {
  val target = normalizeLine(line)
  val caret = primaryCaret()
  caret.moveToOffset(injector.motion.moveCaretToLineWithStartOfLineOption(this, target, caret))
}

/**
 * Moves the caret to [line] keeping the column it had, and not at all if it is already there.
 *
 * `'startofline'` lists the commands that send the caret to the first non-blank, and `<C-E>` and
 * `<C-Y>` are not among them - they are not caret commands. When the view scrolls out from under
 * the caret Vim brings it along in the same column, and when it does not, Vim leaves it alone.
 *
 * "Not at all" is the half that was wrong: this went through `'startofline'` unconditionally, so
 * every `<C-E>` on an indented line moved the caret from column 0 to the first non-blank while the
 * user was asking for the view to move and the caret to stay.
 */
private fun VsCodeEditor.dragCaretToLine(line: Int) {
  val target = normalizeLine(line)
  val caret = primaryCaret()
  if (target == caret.getBufferPosition().line) return
  caret.moveToOffset(injector.motion.moveCaretToLineWithSameColumn(this, target, caret))
}

/**
 * Vim's vertical scrolling, in terms of `visibleRanges` and `editorScroll`.
 *
 * IdeaVim's version of this file works in pixels - it asks IntelliJ for the visible rectangle in
 * y coordinates and converts back and forth - because IntelliJ has block inlays that make a line
 * taller than a line. Nothing here does, so this works in line numbers, which is what Vim's own
 * documentation is written in and makes the arithmetic say what it means.
 */
internal object VsCodeScrollGroup : VimScrollGroup {

  /**
   * Vim's `update_topline`: bring the caret into the window, and do nothing at all if it is already
   * there.
   *
   * The "do nothing" half is the important one, and it was missing. The engine calls this after
   * more or less every command - a single `<Esc>` in the stub host produced twelve of them - and
   * each one moved the view unconditionally. That is a scroll request even when the caret has not
   * moved, and it was computed from the view VS Code had *painted*, so one issued straight after
   * `<C-E>` asked the editor to put a line back on screen that `<C-E>` had just scrolled past. The
   * scroll and the correction behind it fight, and the correction wins because it is last.
   *
   * Vim does not have this problem because `update_topline` returns immediately when the cursor is
   * inside the window, which is what the first guard below is.
   *
   * The second is Vim's rule for how far to scroll once it has to. A motion that steps off an edge
   * - `j` on the last line of the window - scrolls by the one line that puts it back, and a jump
   * that lands somewhere else entirely puts the line it landed on in the middle of the window.
   * `scroll_cursor_bot` in Vim's `move.c` decides between them by the distance: a scroll of a whole
   * window or more is not a scroll, it is arriving somewhere, and arriving somewhere with the line
   * you asked for pinned to the bottom row is a bad place to read from. `G`, `n`, `` ` ``, `%` and
   * a `:` line number all land centred; `j`, `k` and `}` a few lines on do not move the view any
   * more than they have to.
   */
  override fun scrollCaretIntoView(editor: VimEditor) {
    val vsCode = editor as? VsCodeEditor ?: return
    // Nothing is known about the window yet - a command has just changed the document and VS Code
    // has not said where that left the view. Scrolling on the previous document's numbers is what
    // put the first line behind the tab bar.
    //
    // Revealed rather than merely skipped, and that distinction is the whole of the bug this line
    // was reported for a second time. Skipping alone is right for the arithmetic and wrong for the
    // user: `gg` in the stale window moved the caret to line zero, this returned without scrolling,
    // and nothing else was going to show it - so the caret sat above the top of the window. In a
    // window tall enough to hold the whole document that never showed, because the document was
    // visible anyway; with the Output panel open and eight lines of editor, it showed every time.
    //
    // A reveal is the one request that does not need the height. VS Code knows it.
    if (vsCode.viewportIsStale) {
      vsCode.revealPrimaryCaret()
      return
    }
    // The whole document is on screen, so there is nowhere to scroll it to. Worth saying before
    // the arithmetic rather than trusting the arithmetic to reach the same answer, because the
    // height it uses is exactly what a short document makes unreliable.
    if (vsCode.screenTopLine == 0 && vsCode.reportedBottomLine >= editor.lineCount() - 1) return
    // From the buffer rather than from the document: this runs mid-command, before the flush, when
    // the document still has the old text and `positionAt` would answer about that.
    val position = vsCode.offsetToBufferPosition(vsCode.primaryCaret().offset)
    val top = vsCode.screenTopLine
    val height = vsCode.screenHeight
    if (position.line >= top && position.line <= top + height - 1) return

    val minimal = if (position.line < top) position.line else position.line - height + 1
    if (abs(minimal - top) >= height) {
      vsCode.scrollLineToMiddle(position.line)
    } else {
      vsCode.scrollViewTo(minimal)
    }
  }

  /**
   * `<C-F>` and `<C-B>`: a whole window, less two lines kept for context.
   *
   * The caret lands on the first line of the new window going down and the last going up, which is
   * Vim's rule and also the only sensible one: after a page the caret's old line is gone.
   */
  override fun scrollFullPage(editor: VimEditor, caret: VimCaret, pages: Int): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    if (pages == 0) return false
    val height = vsCode.screenHeight
    val step = max(1, height - 2)
    val lastLine = max(0, editor.lineCount() - 1)
    val oldTop = vsCode.screenTopLine
    val newTop = (oldTop + pages * step).coerceIn(0, lastLine)
    if (newTop == oldTop) return false

    // From `newTop` rather than from the editor. Asking where the view is, immediately after asking
    // it to move, gets the old answer in a real window - see [scrollLines].
    val caretLine = if (pages > 0) newTop else min(newTop + height - 1, lastLine)
    vsCode.scrollViewTo(newTop)
    vsCode.moveCaretToLine(caretLine)
    return true
  }

  /**
   * `<C-D>` and `<C-U>`: half a window, or `'scroll'` lines, or a count - which also sets `'scroll'`.
   *
   * The caret keeps its position relative to the window rather than being placed at an edge, so
   * moving it by the same number of lines the view moved is the whole of it.
   */
  override fun scrollHalfPage(editor: VimEditor, caret: VimCaret, rawCount: Int, down: Boolean): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    val lastLine = max(0, editor.lineCount() - 1)
    val caretLine = caret.getBufferPosition().line
    if (down && caretLine >= lastLine) return false
    if (!down && caretLine <= 0) return false

    val options = injector.options(editor)
    if (rawCount > 0) options.scroll = rawCount
    val step = if (options.scroll > 0) options.scroll else max(1, vsCode.screenHeight / 2)
    val signed = if (down) step else -step

    vsCode.scrollViewTo(vsCode.screenTopLine + signed)
    vsCode.moveCaretToLine((caretLine + signed).coerceIn(0, lastLine))
    return true
  }

  /**
   * `<C-E>` and `<C-Y>`: the view by [lines], the caret only if it would be left behind.
   *
   * This used to ask for the new top line and then ask whether the view had moved, treating "it has
   * not" as the scroll having nowhere to go. In a real window the answer is always "it has not" -
   * VS Code scrolls on a later frame - so both keys reported failure and never dragged the caret.
   * Every test passed, because [FakeEditor] used to move the moment it was asked.
   *
   * Whether the scroll has anywhere to go is now decided by arithmetic, before anything is asked,
   * which is also the only way to answer it honestly: it is a question about the file's length, not
   * about what the editor has finished painting.
   */
  override fun scrollLines(editor: VimEditor, lines: Int): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    if (lines == 0) return false
    val lastLine = max(0, editor.lineCount() - 1)
    val oldTop = vsCode.screenTopLine
    val newTop = (oldTop + lines).coerceIn(0, lastLine)
    if (newTop == oldTop) return false

    val caretLine = vsCode.caretLineForView(newTop, vsCode.screenHeight)
    vsCode.scrollViewTo(newTop)
    vsCode.dragCaretToLine(caretLine)
    return true
  }

  override fun scrollCurrentLineToDisplayTop(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    scrollLineToScreen(editor, rawCount, start) { vsCode, line -> vsCode.scrollViewTo(line - vsCode.scrollOffset()) }

  override fun scrollCurrentLineToDisplayMiddle(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    scrollLineToScreen(editor, rawCount, start) { vsCode, line -> vsCode.scrollLineToMiddle(line) }

  override fun scrollCurrentLineToDisplayBottom(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    scrollLineToScreen(editor, rawCount, start) { vsCode, line ->
      vsCode.scrollLineToBottom(line + vsCode.scrollOffset())
    }

  /**
   * `zt`, `zz`, `zb` and their `z<CR>`, `z.`, `z-` variants.
   *
   * A count names a file line rather than a repetition, and the dotted forms also move the caret to
   * the first non-blank of it. Both of those are why this takes the line before it scrolls: after
   * the scroll, "the current line" is a different question.
   */
  private inline fun scrollLineToScreen(
    editor: VimEditor,
    rawCount: Int,
    start: Boolean,
    scroll: (VsCodeEditor, Int) -> Unit,
  ): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    val caret = vsCode.primaryCaret()
    val line = if (rawCount == 0) caret.getBufferPosition().line else editor.normalizeLine(rawCount - 1)
    scroll(vsCode, line)
    if (start) {
      caret.moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, line))
    } else if (line != caret.getBufferPosition().line) {
      caret.moveToOffset(injector.motion.moveCaretToLineWithSameColumn(editor, line, caret))
    }
    return true
  }

  // Sideways. `visibleRanges` carries no columns and there is no API that scrolls by one, so these
  // cannot be answered rather than merely being unwritten. Reporting failure is what Vim does when
  // a scroll has nowhere to go, and it beats a crash and it beats silence.
  override fun scrollColumns(editor: VimEditor, columns: Int): Boolean = false
  override fun scrollCaretColumnToDisplayLeftEdge(vimEditor: VimEditor): Boolean = false
  override fun scrollCaretColumnToDisplayRightEdge(editor: VimEditor): Boolean = false

  override fun onScrollOptionChanged(editor: VimEditor) = scrollCaretIntoView(editor)
}

/**
 * `H`, `M` and `L` - the caret to the top, middle or bottom of what is on screen.
 *
 * A count on `H` or `L` counts lines in from that edge, and `'scrolloff'` sets a floor under it:
 * `H` with `scrolloff=3` lands three lines down whatever the count says, because those three lines
 * are meant to stay above the caret. The exception is the very top or bottom of the file, where
 * there is nothing to keep in view and Vim lets the caret go all the way.
 */
internal fun VsCodeEditor.displayLine(location: ScreenLocation, count: Int, normalizeToScreen: Boolean): Int {
  val offset = if (normalizeToScreen) scrollOffset() else 0
  val top = screenTopLine
  val bottom = screenBottomLine
  val topOffset = if (top > 0) offset else 0
  val bottomOffset = if (bottom < lineCount() - 1) offset else 0
  val line = when (location) {
    ScreenLocation.TOP -> min(top + max(topOffset, count), if (normalizeToScreen) bottom - bottomOffset else bottom)
    ScreenLocation.MIDDLE -> top + (bottom - top) / 2
    ScreenLocation.BOTTOM -> max(bottom - max(bottomOffset, count), if (normalizeToScreen) top + topOffset else top)
  }
  return normalizeLine(line)
}

internal enum class ScreenLocation { TOP, MIDDLE, BOTTOM }
