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

/** The first line on screen. Zero for an editor VS Code has not laid out yet. */
internal val VsCodeEditor.screenTopLine: Int
  get() = nativeEditor.visibleRanges.firstOrNull()?.start?.line ?: 0

/**
 * The last line on screen.
 *
 * Folding would show up as more than one visible range with gaps between them, so this takes the
 * end of the last one rather than adding a height to the top. This host cannot fold, but reading
 * the ranges the way VS Code means them costs nothing.
 */
internal val VsCodeEditor.screenBottomLine: Int
  get() = nativeEditor.visibleRanges.lastOrNull()?.end?.line ?: (lineCount() - 1)

/** How many lines the window shows. At least one, so that arithmetic dividing by it is safe. */
internal val VsCodeEditor.screenHeight: Int
  get() = max(1, screenBottomLine - screenTopLine + 1)

/** Scrolls so that [line] is the first line on screen, as far as the end of the file allows. */
internal fun VsCodeEditor.scrollLineToTop(line: Int) {
  val target = line.coerceIn(0, max(0, lineCount() - 1))
  val at = Position(target, 0)
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.AtTop)
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
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.InCenterIfOutsideViewport)
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
  nativeEditor.revealRange(Range(at, at), TextEditorRevealType.InCenter)
}

/** `'scrolloff'`, capped so that a large value on a small window cannot pin the caret off screen. */
private fun VsCodeEditor.scrollOffset(): Int =
  min(injector.options(this).scrolloff, (screenHeight - 1) / 2)

/**
 * Vim's rule that the caret stays on screen, with `'scrolloff'` lines to spare.
 *
 * The scroll commands move the view first and drag the caret afterwards; this is the dragging.
 * It returns the line the caret should be on, which is its own line whenever that is already a
 * comfortable place to be.
 */
private fun VsCodeEditor.caretLineWithinView(): Int {
  val offset = scrollOffset()
  val top = if (screenTopLine > 0) screenTopLine + offset else screenTopLine
  val bottom = if (screenBottomLine < lineCount() - 1) screenBottomLine - offset else screenBottomLine
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
    vsCode.nativeEditor.revealRange(Range(at, at), TextEditorRevealType.Default)
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
    val step = max(1, vsCode.screenHeight - 2)
    val oldTop = vsCode.screenTopLine
    val newTop = (oldTop + pages * step).coerceIn(0, max(0, editor.lineCount() - 1))
    if (newTop == oldTop) return false
    vsCode.scrollLineToTop(newTop)
    vsCode.moveCaretToLine(if (pages > 0) vsCode.screenTopLine else vsCode.screenBottomLine)
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

  /** `<C-E>` and `<C-Y>`: the view by [lines], the caret only if it would be left behind. */
  override fun scrollLines(editor: VimEditor, lines: Int): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    if (lines == 0) return false
    val oldTop = vsCode.screenTopLine
    vsCode.scrollLineToTop(oldTop + lines)
    if (vsCode.screenTopLine == oldTop) return false
    vsCode.moveCaretToLine(vsCode.caretLineWithinView())
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
