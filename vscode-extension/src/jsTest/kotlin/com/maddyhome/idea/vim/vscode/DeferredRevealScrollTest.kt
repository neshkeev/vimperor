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
import kotlin.test.assertTrue

/**
 * Scrolling when the editor has not painted yet, which is the only kind of editor there is.
 *
 * `revealRange` schedules a scroll; `visibleRanges` describes the view VS Code has actually drawn.
 * Between the two lies a frame that an extension never gets to wait for, so anything that reveals
 * and then asks where the view is reads the answer from *before* its own request.
 *
 * `<C-E>` and `<C-Y>` were built on exactly that. They revealed the new top line, asked whether the
 * view had moved, and treated "no" as the scroll having nowhere to go - so in a real window both
 * keys reported failure and never dragged the caret, every time, on every file. It was reported as
 * "`<C-e>` and `<C-y>` don't work".
 *
 * [VsCodeScrollTest] could not have caught it, and neither could any test written the same way:
 * [FakeEditor] applied a reveal the moment it was asked, so the read-back returned the new view and
 * the command looked correct. That is this port's recurring shape - a fake more permissive than VS
 * Code - and the fix belongs in the fake as much as in the code, which is why
 * `revealsTakeEffectImmediately` now exists and why these tests run with it off.
 *
 * Each test therefore types a key, then paints, then looks. Painting after the command is the whole
 * point: the command has to be right about where the view is going without being able to see it.
 */
class DeferredRevealScrollTest {

  private class Session(lines: Int = 40, caretLine: Int = 0, height: Int = 10, top: Int = 0) {
    val fake = FakeEditor((0 until lines).joinToString("\n") { "line $it" })
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
      fake.reveals.clear()
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

    /**
     * The top lines this host asked for, in order.
     *
     * A scroll asks for a range one window tall; a caret being brought back into view asks for a
     * single line. That is what tells them apart now that both go through `Default`.
     */
    fun topsAskedFor(): List<Int> = fake.reveals.filter { it.end > it.start }.map { it.start }
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
    session.fake.reveals.clear()
    repeat(3) { session.type("<C-Y>") }
    assertEquals(listOf(3, 2, 1), session.topsAskedFor(), "and each press back should undo one")
  }

  @Test
  fun `test Ctrl-Y still refuses at the top of the file`() {
    val session = UnpaintedSession(caretLine = 0)
    session.type("<C-E>")
    session.type("<C-Y>")
    session.fake.reveals.clear()
    session.type("<C-Y>")
    assertEquals(emptyList(), session.topsAskedFor(), "the view is at the top and there is nowhere to go")
  }

  /**
   * A scroll is not followed by a reveal that undoes it.
   *
   * The engine calls `scrollCaretIntoView` after almost every command - twelve times for a single
   * `<Esc>` in the stub host - and it used to reveal the caret unconditionally. `Default` asks for
   * the smallest scroll that puts a line on screen, computed from the view VS Code has painted, so
   * one issued straight after `<C-E>` asks the editor to bring back a line that `<C-E>` had just
   * scrolled past. Last request wins, and it is not the scroll.
   *
   * Vim's `update_topline` returns immediately when the cursor is inside the window. So does this,
   * now, and the assertion is that a `<C-E>` whose caret stays on screen asks for exactly one
   * thing.
   */
  @Test
  fun `test a scroll is not undone by a caret reveal behind it`() {
    val session = UnpaintedSession(caretLine = 5)
    session.type("<C-E>")
    assertEquals(
      listOf(RecordedReveal(1, 10, TextEditorRevealType.AtTop)),
      session.fake.reveals.toList(),
      "the scroll should be the only thing asked of the view, and a whole window of it",
    )
  }

  /** ...and the caret is still brought back when it really has gone off screen. */
  @Test
  fun `test a caret off screen is still revealed`() {
    val session = UnpaintedSession(lines = 200, caretLine = 0)
    session.fake.reveals.clear()
    session.type("100G")
    assertTrue(
      session.fake.reveals.any { it.start == 99 && it.end == 99 },
      "a jump past the window has to bring the caret back: ${session.fake.reveals}",
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
