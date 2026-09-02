/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimScrollGroup
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.normalizeLine
import com.maddyhome.idea.vim.api.options
import kotlin.math.max
import kotlin.math.min

/**
 * Where the view is, and how to move it.
 *
 * Vim and VS Code disagree about what scrolling is. In Vim the view is the thing you move -
 * `<C-E>` moves it down a line, `zt` puts the current line at the top - and the caret comes along
 * only if it would otherwise fall off. VS Code's extension API has no such verb: `revealRange`
 * takes a range and a hint about where to put it, and there is nothing that scrolls by a line.
 *
 * The two are reconcilable because `visibleRanges` completes the conversation. Read the view, do
 * Vim's arithmetic on line numbers, and ask for the line that should end up at the top to be
 * revealed `AtTop`. Every vertical scroll in Vim reduces to that one call.
 *
 * Columns do not. `visibleRanges` is line ranges only - there is no horizontal viewport to read and
 * no way to scroll sideways - so `zh`, `zl`, `zs`, `ze`, `zH` and `zL` are the one group here that
 * cannot be written rather than merely not written yet. They report failure, which is what Vim does
 * when a scroll has nowhere to go.
 */

/** What VS Code says the window shows, which is where it has been painted rather than where it is. */
private val VsCodeEditor.reportedTopLine: Int
  get() = nativeEditor.visibleRanges.firstOrNull()?.start?.line ?: 0

private val VsCodeEditor.reportedBottomLine: Int
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
 * See [VsCodeEditor.revealedTopLine] for what that cost.
 */
internal val VsCodeEditor.screenTopLine: Int
  get() {
    val reported = reportedTopLine
    if (reported != lastReportedTopLine) {
      // The editor has moved of its own accord - the user scrolled, or it caught up with a reveal.
      lastReportedTopLine = reported
      revealedTopLine = null
      return reported
    }
    return revealedTopLine ?: reported
  }

/**
 * How many lines the window shows.
 *
 * From the reported ranges, and it is the one thing they can still be trusted for: a height changes
 * when the window is laid out, not when it scrolls, so a stale pair of ranges is the wrong place
 * and the right size. At least one, so that arithmetic dividing by it is safe.
 */
internal val VsCodeEditor.screenHeight: Int
  get() = max(1, reportedBottomLine - reportedTopLine + 1)

/**
 * The last line on screen, derived from the top and the height.
 *
 * Not read from the ranges: the top no longer comes from them either, and a bottom that did would
 * describe a different window from the top.
 */
internal val VsCodeEditor.screenBottomLine: Int
  get() = min(screenTopLine + screenHeight - 1, max(0, lineCount() - 1))

/** Scrolls so that [line] is the first line on screen, as far as the end of the file allows. */
internal fun VsCodeEditor.scrollLineToTop(line: Int) {
  val target = line.coerceIn(0, max(0, lineCount() - 1))
  val at = Position(target, 0)
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.AtTop)
  rememberRevealed(target)
}

/**
 * Records where a reveal has put the top of the window.
 *
 * Every reveal has to do this, not only the one that names a top line: they all move the view, and
 * a command that moved it without saying so would leave the next one building on a position two
 * commands out of date. The arithmetic for each type is VS Code's own, and the same arithmetic
 * [FakeEditor] models.
 */
private fun VsCodeEditor.rememberRevealed(top: Int) {
  revealedTopLine = top.coerceIn(0, max(0, lineCount() - 1))
  lastReportedTopLine = reportedTopLine
}

/**
 * Brings [line] onto the screen if it is not already, and leaves the view alone if it is.
 *
 * `InCenterIfOutsideViewport` is exactly Vim's behaviour for a search: typing a pattern does not
 * scroll the window while the match you are heading for is already visible.
 */
internal fun VsCodeEditor.scrollLineIntoView(line: Int) {
  val target = line.coerceIn(0, max(0, lineCount() - 1))
  val at = Position(target, 0)
  val top = screenTopLine
  val height = screenHeight
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.InCenterIfOutsideViewport)
  rememberRevealed(if (target < top || target > top + height - 1) target - (height - 1) / 2 else top)
}

/** Scrolls so that [line] is the last line on screen. */
internal fun VsCodeEditor.scrollLineToBottom(line: Int) = scrollLineToTop(line - screenHeight + 1)

/**
 * Scrolls so that [line] is in the middle.
 *
 * `InCenter` is VS Code's own idea of the middle rather than this file's arithmetic, which is the
 * better answer: it is what the editor does for "go to definition" and it accounts for whatever the
 * window is actually showing.
 */
internal fun VsCodeEditor.scrollLineToMiddle(line: Int) {
  val target = line.coerceIn(0, max(0, lineCount() - 1))
  val at = Position(target, 0)
  val height = screenHeight
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.InCenter)
  rememberRevealed(target - (height - 1) / 2)
}

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
 * `revealRange` is asynchronous: VS Code scrolls on a later frame, so `visibleRanges` goes on
 * describing where the view *was* until then. Anything that reveals and then asks where the view is
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
 * Vim's vertical scrolling, in terms of `visibleRanges` and `revealRange`.
 *
 * IdeaVim's version of this file works in pixels - it asks IntelliJ for the visible rectangle in
 * y coordinates and converts back and forth - because IntelliJ has block inlays that make a line
 * taller than a line. Nothing here does, so this works in line numbers, which is what Vim's own
 * documentation is written in and makes the arithmetic say what it means.
 */
internal object RevealingScrollGroup : VimScrollGroup {

  override fun scrollCaretIntoView(editor: VimEditor) {
    val vsCode = editor as? VsCodeEditor ?: return
    // From the buffer rather than from the document: this runs mid-command, before the flush, when
    // the document still has the old text and `positionAt` would answer about that.
    val position = vsCode.offsetToBufferPosition(vsCode.primaryCaret().offset)
    val at = Position(position.line, position.column)
    val top = vsCode.screenTopLine
    val height = vsCode.screenHeight
    vsCode.nativeEditor.revealRange(Range(at, at), TextEditorRevealType.Default)
    // Default is the smallest scroll that brings the line on screen, which is usually none at all.
    vsCode.rememberRevealed(
      when {
        position.line < top -> position.line
        position.line > top + height - 1 -> position.line - height + 1
        else -> top
      },
    )
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
    vsCode.scrollLineToTop(newTop)
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

    vsCode.scrollLineToTop(vsCode.screenTopLine + signed)
    vsCode.moveCaretToLine((caretLine + signed).coerceIn(0, lastLine))
    return true
  }

  /**
   * `<C-E>` and `<C-Y>`: the view by [lines], the caret only if it would be left behind.
   *
   * This used to reveal the new top line and then ask whether the view had moved, treating "it has
   * not" as the scroll having nowhere to go. In a real window the answer is always "it has not" -
   * `revealRange` scrolls on a later frame - so both keys reported failure and never dragged the
   * caret. Every test passed, because [FakeEditor] applies a reveal the moment it is asked.
   *
   * Whether the scroll has anywhere to go is now decided by arithmetic, before anything is
   * revealed, which is also the only way to answer it honestly: it is a question about the file's
   * length, not about what the editor has finished painting.
   */
  override fun scrollLines(editor: VimEditor, lines: Int): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    if (lines == 0) return false
    val lastLine = max(0, editor.lineCount() - 1)
    val oldTop = vsCode.screenTopLine
    val newTop = (oldTop + lines).coerceIn(0, lastLine)
    if (newTop == oldTop) return false

    val caretLine = vsCode.caretLineForView(newTop, vsCode.screenHeight)
    vsCode.scrollLineToTop(newTop)
    vsCode.moveCaretToLine(caretLine)
    return true
  }

  override fun scrollCurrentLineToDisplayTop(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    scrollLineToScreen(editor, rawCount, start) { vsCode, line -> vsCode.scrollLineToTop(line - vsCode.scrollOffset()) }

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
