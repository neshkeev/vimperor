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
 * Columns do not. `visibleRanges` is line ranges only, so there is no horizontal viewport to read.
 * `zh`, `zl`, `zH` and `zL` move the view by columns with `editorScroll`, which can scroll sideways
 * by a count though not to a place; `zs` and `ze` let VS Code bring the caret in from the far side.
 * Neither needs to know where the window was.
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
 * it was clipped.
 *
 * A fresh report that ends before the last line looks like the height and is not always one. It is
 * believed outright while it is as tall as anything seen before; a *shorter* one has to contain the
 * caret as well, because the two things that produce one are a window laid out smaller and a window
 * VS Code has not painted yet, and only the second can leave the caret outside what it reports. See
 * [reportDescribesTheCaret], which is where the measurement is.
 *
 * The cost runs the way it should. Too small is silent and permanent - once the view sits past the
 * end of the file every later report is clipped, so nothing can raise a floor back up - while too
 * large corrects itself on the next honest report and is a floor until then, which sends a downward
 * scroll to a reveal rather than to this number.
 *
 * At least one, so that arithmetic dividing by it is safe.
 */
internal val VsCodeEditor.screenHeight: Int
  get() {
    val reported = max(1, reportedBottomLine - reportedTopLine + 1)
    val onlyAFloor = reportedBottomLine >= paintedLastLine || viewportIsStale
    val remembered = knownScreenHeight ?: 0
    val height = when {
      // Clipped to the text, or describing a document that has moved on: a floor, never the height.
      onlyAFloor -> max(reported, remembered)
      // As tall as anything seen before, so it is the height - and this is also the ordinary case of
      // a caret that has just stepped off the bottom row, where the report describes the view the
      // caret has left and measured this window perfectly well on the way.
      reported >= remembered -> reported
      // Shorter than this window has been, and the caret is in it: the window was laid out smaller.
      reportDescribesTheCaret -> reported
      // Shorter, and the caret is nowhere in it, which is not a window VS Code was showing.
      else -> remembered
    }
    knownScreenHeight = height
    // Whether that number is the window's height or merely a floor under it, which is what
    // [VsCodeScrollGroup.scrollCaretIntoView] cannot decide a downward scroll without.
    //
    // A clipped report makes it a floor even when [knownScreenHeight] is larger, and that is the
    // case worth keeping: what is remembered can be too large as well as too small - a window
    // shortened by opening a panel while the view sat at the end of the file goes on reporting
    // clipped ranges, so nothing ever lowers it. Where the remembered height *is* right, the caret
    // is inside the window and `scrollCaretIntoView` has returned before asking this.
    //
    // A height kept over a shorter report is a floor for the same reason, and that is the whole of
    // what the branch above costs: the report is the only fresh measurement there is, and this is
    // not it, so a scroll that would need the number exactly goes to VS Code instead.
    screenHeightIsFloor = onlyAFloor || height != reported
    return height
  }

/**
 * Whether the window VS Code is describing is one the caret could be sitting in.
 *
 * The one thing that separates a window laid out smaller from a report taken before the editor has
 * painted, and nothing else does: both arrive as an unclipped range narrower than the window was a
 * moment ago, which is indistinguishable from a measurement.
 *
 * **Measured, in a trace of the bug.** The view sat at `2405..2431` of a 2432-line file - 27 rows,
 * caret on 2419. Activating another editor and coming back reported `2405..2414`: the *same top*, a
 * span of ten, nothing scrolled and nothing resized. [adoptCaretsFrom] already records why - VS Code
 * restores a tab's view state *after* it reports the editor as active - and the caret being five
 * lines below the bottom of that range is what gives it away, because a window VS Code is showing
 * has the caret in it. It reveals the caret, and Vim never leaves the cursor off screen either.
 *
 * Believing that report set the remembered height to ten. Every report afterwards was clipped to the
 * last line, which can only raise a floor, so ten was permanent - and `zb` on the last line asked
 * for a top of `2431 - 10 + 1`, which is where the view already was, so it did nothing at all. The
 * window had seventeen blank rows under the last line at the time.
 *
 * Only consulted for a report *shorter* than the remembered height, which is what keeps the ordinary
 * case out of it: a caret that has just stepped off the bottom row is outside the report too, and
 * that report is a good measurement of a window that did not change.
 *
 * VS Code's caret rather than the engine's, because the question is about VS Code's report: the
 * engine's buffer runs a keystroke ahead of the document the ranges were measured against.
 */
private val VsCodeEditor.reportDescribesTheCaret: Boolean
  get() {
    val line = nativeEditor.selection.active.line
    return line >= reportedTopLine && line <= reportedBottomLine
  }

/**
 * The last line of the document `visibleRanges` was describing, which is not always this one.
 *
 * The engine edits its buffer and asks to be scrolled in the same keystroke, and the editor is
 * written to afterwards - so in between, `lineCount()` counts a line VS Code has not been told
 * about. Asking the *native* document instead is what keeps "the report stops at the end of the
 * text" a question about the text the report was made against: `o` on the last line of a
 * hundred-line file grew the buffer to 104 lines, and a report of `89..102` that had been clipped
 * to the end a moment ago stopped looking clipped, so a floor of fourteen lines was taken for the
 * window's height and the view scrolled away from a screen half full of blank space.
 */
private val VsCodeEditor.paintedLastLine: Int
  get() = max(0, nativeEditor.document.lineCount - 1)

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
  // Before the early return: `zt` on a line already at the top is still a request to see the caret.
  scrollRequestedSinceFlush = true
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
  // Bringing a match into view, not `zz`: Vim pads past the end of the file for one and not the
  // other. See [scrollLineToMiddleOfTheText].
  scrollLineToMiddleOfTheText(target)
}

/**
 * Scrolls so that [line] is the last line on screen.
 *
 * Arithmetic where the height is known, and VS Code's own reveal where it is not - which is the one
 * place in this file that asks for a reveal at all, and it is here because **this is the one command
 * whose answer the host cannot compute.**
 *
 * While the view reaches the end of the document every report is clipped to the text, and a clipped
 * report's span is `lastLine - top + 1`: a function of the scroll position, carrying no information
 * about the window's height whatever. So a window that changed size while the view sat there - the
 * output panel being opened or closed, which an extension is told nothing about either - can never
 * be learnt, and `zb` goes on asking for `lastLine - staleHeight + 1`. When the window grew, that is
 * where the view already is, so `scrollViewTo` returns on a delta of zero and nothing happens at
 * all. Reported twice: the last line stays halfway up the window with blank rows under it.
 *
 * [VsCodeCommands.REVEAL_LINE] is the only API that names a position *relative to the window*, so it
 * is the only way to place a line without knowing how tall the window is. What it costs is the
 * padding: VS Code puts the line `max(margin, 1)` rows above the bottom rather than on it, by
 * [VsCodeEditor.revealMargin] - the "five lines out" this file has recorded since the first
 * `revealRange` measurement, now read out of `_computeScrollTopToRevealRange` and computed from the
 * settings it comes from. One `editorScroll` of exactly that many lines puts it right, and neither
 * step needs a height.
 *
 * Two guards, and a floor is enough for both because each is monotone in the height. The margin is
 * capped at half the window, which cannot be read, so this only asks where the window is known to be
 * at least twice the margin. And VS Code top-aligns instead when the padded line does not fit, which
 * takes one line plus the margin above plus the correction below.
 */
internal fun VsCodeEditor.scrollLineToBottom(line: Int) {
  val height = screenHeight
  if (screenHeightIsFloor) {
    val margin = revealMargin()
    val correction = max(margin, 1)
    if (height >= 2 * margin && height > 1 + margin + correction) {
      revealLineAtBottom(line, correction)
      return
    }
  }
  scrollViewTo(line - height + 1)
}

/**
 * Hands the placing of [line] to VS Code and takes its padding back off.
 *
 * Nothing is believed about where this leaves the view: the answer is `line + 1 - height` and the
 * height is exactly what is not known. The next report is the only thing that knows - and it is a
 * good one, because once the last line really is on the bottom row the clipped span *is* the
 * height, so one of these teaches [VsCodeEditor.knownScreenHeight] the number and the next `zb` is
 * arithmetic again.
 */
private fun VsCodeEditor.revealLineAtBottom(line: Int, correction: Int) {
  // As in `scrollViewTo`: a scroll that moves nothing is still a request to see the caret.
  scrollRequestedSinceFlush = true
  if (scrollLog.size < 12) scrollLog += "${screenTopLine}->reveal($line at bottom, then up $correction)"
  commands.executeCommand(
    VsCodeCommands.REVEAL_LINE,
    json(
      "lineNumber" to line,
      "at" to "bottom",
    ),
  )
  commands.executeCommand(
    VsCodeCommands.EDITOR_SCROLL,
    json(
      "to" to "up",
      "by" to "line",
      "value" to correction,
      "revealCursor" to false,
    ),
  )
  believedTopLine = null
  lastReportedTopLine = null
}

/** Scrolls so that [line] is in the middle, which is where Vim's `zz` puts it. */
internal fun VsCodeEditor.scrollLineToMiddle(line: Int) = scrollViewTo(line - (screenHeight - 1) / 2)

/**
 * The same, except that the empty rows past the last line do not count towards the half.
 *
 * **Vim has both of these, and the difference is one flag.** `scroll_cursor_halfway(atend,
 * prefer_above)` is the function; `zz` and `z.` pass `atend = TRUE` and are [scrollLineToMiddle] -
 * the line goes to the middle of the *window*, with `~` filler below it where the file has run out,
 * which is the whole point of `zz` on the last line. Everything that merely brings the cursor into
 * view passes `FALSE`, `scroll_cursor_bot` included, and that is the path `G` takes.
 *
 * With the flag clear Vim counts a `~` line as context below the cursor but **does not charge it a
 * screen row** - `++below; if (atend) ++used;` - so the loop goes on adding real lines above until
 * the window is full. That is the clamp below: centre the line, and never show past the last one.
 * Near the end of a file it stops being a centring at all, which is why `G` in Vim leaves the last
 * line on the bottom row.
 *
 * This host centred both, so `G` on the last line of a 2433-line file left the view at 2419 with
 * thirteen blank rows under it where Vim leaves it at 2406. It was reported as `zb` being needed
 * after every `G` - and `zb` was asking for 2406, which is the same number reached from the other
 * end.
 */
internal fun VsCodeEditor.scrollLineToMiddleOfTheText(line: Int) {
  val height = screenHeight
  // As far down as the view may go: the top that puts the last line on the bottom row.
  val lastTop = max(0, lineCount() - height)
  scrollViewTo(min(line - (height - 1) / 2, lastTop))
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

    // Below the window, as far as a floor can tell - and when the height is only a floor that is not
    // far enough. VS Code clips `visibleRanges` to the text, so a window with blank space under the
    // last line reports only the lines it has: forty rows over a file ending at line 102 report
    // `89..102`, and so do fourteen. `o` on that last line puts the caret one line further down, and
    // on the floor's arithmetic it is off screen in both - while in the first it has half a window
    // of room. This was reported as the file scrolling up for no reason.
    //
    // Nothing an extension can read separates those two windows, so the answer is not a better
    // number: it is to let the editor decide. A reveal after the flush scrolls the minimum, or not at
    // all, against the document and caret VS Code has by then - which is also why it waits for the
    // flush, since right now the line the caret is on does not exist in the editor yet.
    if (position.line > top + height - 1 && vsCode.screenHeightIsFloor) {
      vsCode.revealCaretAfterFlush = true
      return
    }

    val minimal = if (position.line < top) position.line else position.line - height + 1
    if (abs(minimal - top) >= height) {
      // Arriving somewhere rather than scrolling, so the line goes to the middle - but not past the
      // end of the file, which is `scroll_cursor_bot` passing `atend` false. See
      // [scrollLineToMiddleOfTheText].
      vsCode.scrollLineToMiddleOfTheText(position.line)
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

  // Sideways. `visibleRanges` carries no columns, so nothing here can know where the window starts
  // or how wide it is - but VS Code can move it by columns, and can bring a column in from either
  // side, and those two are enough for all six keys. See `VsCodeEditor.scrollSideways` and
  // `scrollCaretToEdge`.

  /**
   * `zl`, `zh`, and `zL`, `zH` - which the engine hands over as `getApproximateScreenWidth / 2`
   * columns, forty, because half of a width nobody can read is the best it has.
   *
   * Vim moves the caret when the scroll would leave it off screen, and this cannot: whether it is
   * off screen is exactly what cannot be read. So the caret stays, and the next key that moves it
   * brings the window back to it.
   */
  override fun scrollColumns(editor: VimEditor, columns: Int): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    if (columns == 0) return false
    vsCode.scrollSideways(columns)
    return true
  }

  /** `zs`. */
  override fun scrollCaretColumnToDisplayLeftEdge(vimEditor: VimEditor): Boolean =
    (vimEditor as? VsCodeEditor)?.scrollCaretToEdge(left = true) ?: false

  /** `ze`. */
  override fun scrollCaretColumnToDisplayRightEdge(editor: VimEditor): Boolean =
    (editor as? VsCodeEditor)?.scrollCaretToEdge(left = false) ?: false

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
