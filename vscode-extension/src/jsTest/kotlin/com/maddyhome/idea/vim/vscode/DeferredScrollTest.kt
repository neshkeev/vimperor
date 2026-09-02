/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Scrolling when the editor has not painted yet, which is the only kind of editor there is.
 *
 * A scroll is scheduled; `visibleRanges` describes the view VS Code has actually drawn. Between the
 * two lies a frame that an extension never gets to wait for, so anything that scrolls and then asks
 * where the view is reads the answer from *before* its own request.
 *
 * `<C-E>` and `<C-Y>` were built on exactly that. They asked for the new top line, asked whether the
 * view had moved, and treated "no" as the scroll having nowhere to go - so in a real window both
 * keys reported failure and never dragged the caret, every time, on every file. It was reported as
 * "`<C-e>` and `<C-y>` don't work".
 *
 * [VsCodeScrollTest] could not have caught it, and neither could any test written the same way:
 * [FakeEditor] moved the moment it was asked, so the read-back returned the new view and the
 * command looked correct. That is this port's recurring shape - a fake more permissive than VS Code
 * - and the fix belongs in the fake as much as in the code, which is why
 * `revealsTakeEffectImmediately` now exists and why these tests run with it off.
 *
 * Each test therefore types a key, then paints, then looks. Painting after the command is the whole
 * point: the command has to be right about where the view is going without being able to see it.
 */
class DeferredScrollTest {

  private class Session(
    lines: Int = 40,
    caretLine: Int = 0,
    height: Int = 10,
    top: Int = 0,
    text: String = (0 until lines).joinToString("\n") { "line $it" },
  ) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      fake.viewportHeight = height
      fake.topLine = top
      // The editor VS Code actually gives an extension: a reveal is a request, not a change.
      fake.revealsTakeEffectImmediately = false

      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      KeyHandler.getInstance().fullReset(editor)
      editor.primaryCaret().moveToOffsetNative(editor.getLineStartOffset(caretLine))
    }

    /** Types [keys], then lets the editor paint what they asked for. */
    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
      fake.paint()
    }

    val top: Int get() = fake.topLine
    val caretLine: Int get() = editor.primaryCaret().getBufferPosition().line
    val caretColumn: Int get() = editor.primaryCaret().getBufferPosition().column
  }

  /**
   * The six keys, in the terms they were asked for.
   *
   * Written from a bug report that spelled out what each one should do rather than what it did:
   * "assuming the page size is 20 lines what I expect". So these use a window of that size and say
   * it in the report's own line numbers - one-based, the way the editor's gutter numbers them and
   * the way the reader counted them - with the zero-based line the code works in beside it.
   *
   * Four of the six are exactly what was asked for. `<C-F>` and `<C-B>` are not, and deliberately:
   * Vim keeps two lines of the old page at the far edge of the new one so there is something to
   * read across the join, and the report expected a clean cut with none. Vim's rule is what is
   * asserted, because matching Vim is what this fork is for.
   */
  private fun page(top: Int, caretLine: Int) =
    Session(lines = 200, caretLine = caretLine - 1, height = 21, top = top - 1)

  @Test
  fun `test Ctrl-E scrolls the buffer one line and leaves the caret alone`() {
    // Showing 8-28, caret comfortably inside. After: 9-29, caret where it was.
    val session = page(top = 8, caretLine = 16)
    session.type("<C-E>")
    assertEquals(8, session.top, "the window's first line, zero-based: line 9")
    assertEquals(15, session.caretLine, "the caret was on screen and stays where it is")
  }

  @Test
  fun `test Ctrl-Y scrolls the buffer back one line and leaves the caret alone`() {
    val session = page(top = 8, caretLine = 16)
    session.type("<C-Y>")
    assertEquals(6, session.top, "line 7")
    assertEquals(15, session.caretLine)
  }

  /**
   * `<C-E>` does not touch the column either.
   *
   * `'startofline'` names the commands that send the caret to the first non-blank, and neither of
   * the two scrolling keys is among them. This went through it anyway, so every press on an
   * indented file moved the caret from column 0 to the indent - visible in a real window's trace as
   * `col0` becoming `col5` on a key that is not supposed to move the caret at all.
   */
  @Test
  fun `test Ctrl-E does not send the caret to the first non-blank`() {
    val session = Session(
      lines = 200,
      caretLine = 15,
      height = 21,
      top = 7,
      text = (0 until 200).joinToString("\n") { "     line $it" },
    )
    session.type("<C-E>")
    assertEquals(15, session.caretLine)
    assertEquals(0, session.caretColumn, "the caret was in the indent and had no reason to leave it")
  }

  @Test
  fun `test Ctrl-F moves the caret a page down, less the two lines Vim keeps`() {
    // Showing 8-28 with the caret on 8. Vim's new page starts at 27, not 29: the last two lines of
    // the old page are the first two of the new one.
    val session = page(top = 8, caretLine = 8)
    session.type("<C-F>")
    assertEquals(26, session.top, "line 27")
    assertEquals(26, session.caretLine, "the caret goes to the first line of the new page")
  }

  @Test
  fun `test Ctrl-B moves the caret a page up, less the two lines Vim keeps`() {
    // Showing 28-48 with the caret on 28. The new page ends at 29, so the caret lands there.
    val session = page(top = 28, caretLine = 28)
    session.type("<C-B>")
    assertEquals(8, session.top, "line 9")
    assertEquals(28, session.caretLine, "the caret goes to the last line of the new page: line 29")
  }

  @Test
  fun `test Ctrl-U moves the caret half a page up`() {
    // Showing 28-48 with the caret on 48. Half of twenty is ten, so the caret goes to 38.
    val session = page(top = 28, caretLine = 48)
    session.type("<C-U>")
    assertEquals(37, session.caretLine, "line 38")
    assertEquals(17, session.top, "and the view came with it")
  }

  @Test
  fun `test Ctrl-D moves the caret half a page down`() {
    val session = page(top = 28, caretLine = 28)
    session.type("<C-D>")
    assertEquals(37, session.caretLine, "line 38")
    assertEquals(37, session.top)
  }

  // Where a caret that has left the window is put back, which is not the same question as how far
  // the view has to move to do it.

  /**
   * A jump puts the line it landed on in the middle.
   *
   * The alternative - the smallest scroll that puts the line back on screen - leaves `G` reading
   * the last line of a file off the bottom row of the window and `n` reading a match off the
   * bottom row, with nothing after it. Vim scrolls the minimum for a step off an edge and centres
   * for anything a window or more away; `scroll_cursor_bot` is where it decides.
   */
  @Test
  fun `test a jump lands in the middle of the window`() {
    val session = Session(lines = 200, caretLine = 0, height = 21, top = 0)
    session.type("100G")
    assertEquals(99, session.caretLine)
    assertEquals(89, session.top, "line 100 should be the eleventh of twenty-one rows")
  }

  @Test
  fun `test a step off the bottom edge still scrolls one line`() {
    // The caret is on the last row of the window, so `j` has to move the view - by one line, not by
    // half a window. This is the case centring must not swallow: it is most of what scrolling is.
    val session = Session(lines = 200, caretLine = 20, height = 21, top = 0)
    session.type("j")
    assertEquals(21, session.caretLine)
    assertEquals(1, session.top)
  }

  @Test
  fun `test a jump that lands just past the window scrolls rather than centring`() {
    // Four lines past the bottom row: Vim brings it on screen and no further.
    val session = Session(lines = 200, caretLine = 0, height = 21, top = 0)
    session.type("25G")
    assertEquals(24, session.caretLine)
    assertEquals(4, session.top)
  }

  @Test
  fun `test a jump backwards lands in the middle too`() {
    val session = Session(lines = 200, caretLine = 150, height = 21, top = 140)
    session.type("20G")
    assertEquals(19, session.caretLine)
    assertEquals(9, session.top)
  }

  @Test
  fun `test a jump to the top of the file has nowhere to centre and goes to the top`() {
    val session = Session(lines = 200, caretLine = 150, height = 21, top = 140)
    session.type("gg")
    assertEquals(0, session.caretLine)
    assertEquals(0, session.top)
  }

  @Test
  fun `test the centring is asked for once, of a window that never reports moving`() {
    val session = UnpaintedSession(lines = 200, caretLine = 0, height = 21)
    session.type("100G")
    assertEquals(listOf(89), session.topsAskedFor(), "one scroll, to the centred top line")
  }

  // <C-E> and <C-Y>, the two that were reported.

  @Test
  fun `test Ctrl-E scrolls even though the view has not painted`() {
    val session = Session(caretLine = 5)
    session.type("<C-E>")
    assertEquals(1, session.top)
    assertEquals(5, session.caretLine, "the caret was still on screen and should not have moved")
  }

  @Test
  fun `test Ctrl-E still drags the caret off the top`() {
    val session = Session(caretLine = 0)
    session.type("<C-E>")
    assertEquals(1, session.top)
    assertEquals(1, session.caretLine, "the caret's line scrolled away and it has to come with it")
  }

  /**
   * Holding `<C-E>` keeps scrolling, and the caret keeps up with it.
   *
   * What was measured, by putting the old code back: the view moves, the caret does not, and the
   * command reports failure. So the caret is left above the window and stays there however many
   * times the key is pressed, and every press is a failed command.
   *
   * What a window does with a caret parked off screen and a beep on every keystroke was not
   * measured and is not asserted here - it needs a real one. This asserts the part that is this
   * host's to get right: after N presses the view has moved N lines and the caret has come with it.
   */
  @Test
  fun `test Ctrl-E pressed repeatedly keeps scrolling`() {
    val session = Session(caretLine = 0)
    for (expected in 1..5) {
      session.type("<C-E>")
      assertEquals(expected, session.top, "press $expected should have moved the view")
      assertEquals(expected, session.caretLine, "and dragged the caret it left behind")
    }
  }

  @Test
  fun `test Ctrl-Y pressed repeatedly keeps scrolling back`() {
    val session = Session(caretLine = 29, top = 20)
    for (step in 1..5) {
      session.type("<C-Y>")
      assertEquals(20 - step, session.top, "press $step should have moved the view")
      assertEquals(29 - step, session.caretLine, "and dragged the caret off the bottom")
    }
  }

  @Test
  fun `test Ctrl-Y scrolls back up`() {
    val session = Session(caretLine = 12, top = 10)
    session.type("<C-Y>")
    assertEquals(9, session.top)
    assertEquals(12, session.caretLine)
  }

  @Test
  fun `test Ctrl-Y drags the caret off the bottom`() {
    val session = Session(caretLine = 19, top = 10)
    session.type("<C-Y>")
    assertEquals(9, session.top)
    assertEquals(18, session.caretLine)
  }

  @Test
  fun `test a count still scrolls that many lines`() {
    val session = Session(caretLine = 20, top = 15)
    session.type("3<C-E>")
    assertEquals(18, session.top)
  }

  /**
   * The edges still refuse, and for the right reason.
   *
   * This is what the read-back was trying to answer, and the reason it had to be replaced rather
   * than deleted: "the view did not move" is a real thing that happens at the top of a file. It is
   * now decided by arithmetic on the file's length, which is a question the editor's paint queue
   * has no opinion about.
   */
  @Test
  fun `test Ctrl-Y at the top of the file does nothing`() {
    val session = Session(caretLine = 0)
    session.type("<C-Y>")
    assertEquals(0, session.top)
    assertEquals(0, session.caretLine)
  }

  @Test
  fun `test Ctrl-E at the last line of the file does nothing`() {
    val session = Session(lines = 12, caretLine = 11, top = 11)
    session.type("<C-E>")
    assertEquals(11, session.top)
  }

  // <C-F> and <C-B>, which had the same bug in the half of them that places the caret.

  @Test
  fun `test Ctrl-F puts the caret on the new top line`() {
    val session = Session(caretLine = 3)
    session.type("<C-F>")
    assertEquals(8, session.top, "a window less the two lines Vim keeps for context")
    assertEquals(8, session.caretLine, "the caret goes to the first line of the new window")
  }

  @Test
  fun `test Ctrl-B puts the caret on the new bottom line`() {
    val session = Session(caretLine = 22, top = 20)
    session.type("<C-B>")
    assertEquals(12, session.top)
    assertEquals(21, session.caretLine, "the caret goes to the last line of the new window")
  }

  /**
   * The window that never reports where it is - which is the one the bug was reported from.
   *
   * A trace from a real window showed `view=[0..13] of 924` after every single press of `<C-E>` and
   * `<C-Y>`, over a dozen of them. The keys arrived, the commands ran, `revealRange` was called -
   * and `visibleRanges` said the same thing throughout. Reading the top line from it meant every
   * command started from line 0, so `<C-E>` asked for line 1 again and again and `<C-Y>` clamped to
   * 0 and refused. Both keys did nothing, for ever.
   *
   * This models exactly that: reveals are deferred and never painted, so `visibleRanges` is frozen
   * at the start. What is asserted is what the host *asked for*, because that is the only thing a
   * scroll command controls.
   */
  private class UnpaintedSession(lines: Int = 40, caretLine: Int = 0, height: Int = 10) {
    val session = Session(lines = lines, caretLine = caretLine, height = height)
    val fake get() = session.fake

    init {
      fake.scrolls.clear()
    }

    /** Types [keys] and never paints, so the reported viewport stays where it started. */
    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(session.editor, stroke, VsCodeExecutionContext, state)
      }
      session.editor.flush()
    }

    /** The top lines this host asked for, in order. */
    fun topsAskedFor(): List<Int> = fake.scrolls.toList()
  }

  @Test
  fun `test Ctrl-E keeps advancing when the viewport never reports moving`() {
    val session = UnpaintedSession(caretLine = 5)
    repeat(5) { session.type("<C-E>") }
    assertEquals(listOf(1, 2, 3, 4, 5), session.topsAskedFor(), "each press should ask for the next line")
  }

  @Test
  fun `test Ctrl-Y comes back down the same way`() {
    val session = UnpaintedSession(caretLine = 5)
    repeat(4) { session.type("<C-E>") }
    session.fake.scrolls.clear()
    repeat(3) { session.type("<C-Y>") }
    assertEquals(listOf(3, 2, 1), session.topsAskedFor(), "and each press back should undo one")
  }

  @Test
  fun `test Ctrl-Y still refuses at the top of the file`() {
    val session = UnpaintedSession(caretLine = 0)
    session.type("<C-E>")
    session.type("<C-Y>")
    session.fake.scrolls.clear()
    session.type("<C-Y>")
    assertEquals(emptyList(), session.topsAskedFor(), "the view is at the top and there is nowhere to go")
  }

  /**
   * A scroll is not followed by a correction that undoes it.
   *
   * The engine calls `scrollCaretIntoView` after almost every command - twelve times for a single
   * `<Esc>` in the stub host - and it used to move the view unconditionally. It asked for the
   * smallest scroll that puts the caret on screen, computed from the view VS Code has painted, so
   * one issued straight after `<C-E>` asks the editor to bring back a line that `<C-E>` had just
   * scrolled past. Last request wins, and it is not the scroll.
   *
   * Vim's `update_topline` returns immediately when the cursor is inside the window. So does this,
   * now, and the assertion is that a `<C-E>` whose caret stays on screen asks for exactly one
   * thing.
   */
  @Test
  fun `test a scroll is not undone by a caret correction behind it`() {
    val session = UnpaintedSession(caretLine = 5)
    session.type("<C-E>")
    assertEquals(
      listOf(1),
      session.fake.scrolls.toList(),
      "the scroll should be the only thing asked of the view",
    )
    assertEquals(emptyList(), session.fake.reveals.toList(), "and nothing should have been revealed")
  }

  /** ...and the caret is still brought back when it really has gone off screen. */
  @Test
  fun `test a caret off screen is still brought back`() {
    // Two lines past the bottom of a ten-line window, so this is the smallest-scroll case rather
    // than the centring one - see `test a jump lands in the middle of the window` for that.
    val session = UnpaintedSession(lines = 200, caretLine = 0)
    session.fake.scrolls.clear()
    session.type("12G")
    assertEquals(
      listOf(2),
      session.fake.scrolls.toList(),
      "a caret two lines past the window has to bring the view two lines after it",
    )
  }

  // <C-D> and <C-U> never read the view back, and are here so that stays true.

  @Test
  fun `test Ctrl-D moves the view and the caret together`() {
    val session = Session(caretLine = 5)
    session.type("<C-D>")
    assertEquals(5, session.top)
    assertEquals(10, session.caretLine)
  }

  @Test
  fun `test Ctrl-U moves them back`() {
    val session = Session(caretLine = 25, top = 20)
    session.type("<C-U>")
    assertEquals(15, session.top)
    assertEquals(20, session.caretLine)
  }
}
