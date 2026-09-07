/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Scrolling: `<C-E>`, `<C-D>`, `<C-F>`, `zt`, `zz`, `zb`, and `H`/`M`/`L`.
 *
 * These are the keys the port's own inventory named as the biggest gap, and they are gone from it
 * now. What made them a group is that they all say the same thing in the same way: Vim moves the
 * *view* and lets the caret follow, where VS Code will only reveal a range. `visibleRanges`
 * completes the conversation - read where the view is, do Vim's arithmetic in line numbers, and
 * reveal the line that should end up at the top.
 *
 * Every test here works a ten-line window over a forty-line file, and asserts both halves: where
 * the view ended up and where the caret ended up. Asserting only the caret would pass for a command
 * that never scrolled, and asserting only the view would pass for one that left the caret off
 * screen.
 */
class VsCodeScrollTest {

  private class Session(lines: Int = 40, caretLine: Int = 0, height: Int = 10) {
    val fake = FakeEditor((0 until lines).joinToString("\n") { "line $it" })
    val editor: VsCodeEditor

    init {
      fake.viewportHeight = height
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      KeyHandler.getInstance().fullReset(editor)
      editor.primaryCaret().moveToOffsetNative(editor.getLineStartOffset(caretLine))
    }

    /** Puts the window where a test wants it, without going through a command to do it. */
    fun scrollTo(top: Int) {
      fake.topLine = top
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
    }

    val top: Int get() = fake.topLine
    val caretLine: Int get() = editor.primaryCaret().getBufferPosition().line
  }

  // <C-E> and <C-Y> - the view by a line, the caret only when it would be left behind.

  @Test
  fun `test Ctrl-E scrolls the view down one line`() {
    val session = Session(caretLine = 5)
    session.type("<C-E>")
    assertEquals(1, session.top)
    assertEquals(5, session.caretLine, "the caret was still on screen and should not have moved")
  }

  @Test
  fun `test Ctrl-E drags the caret off the top of the window`() {
    val session = Session(caretLine = 0)
    session.type("<C-E>")
    assertEquals(1, session.top)
    assertEquals(1, session.caretLine)
  }

  @Test
  fun `test Ctrl-Y scrolls the view back up`() {
    val session = Session(caretLine = 12)
    session.scrollTo(10)
    session.type("<C-Y>")
    assertEquals(9, session.top)
    assertEquals(12, session.caretLine)
  }

  @Test
  fun `test Ctrl-Y drags the caret off the bottom of the window`() {
    val session = Session(caretLine = 19)
    session.scrollTo(10)
    session.type("<C-Y>")
    assertEquals(9, session.top)
    assertEquals(18, session.caretLine)
  }

  @Test
  fun `test a count scrolls that many lines`() {
    val session = Session(caretLine = 20)
    session.scrollTo(15)
    session.type("3<C-E>")
    assertEquals(18, session.top)
  }

  @Test
  fun `test Ctrl-Y at the top of the file does nothing`() {
    val session = Session(caretLine = 0)
    session.type("<C-Y>")
    assertEquals(0, session.top)
    assertEquals(0, session.caretLine)
  }

  // <C-F> and <C-B> - a window at a time, less the two lines Vim keeps for context.

  @Test
  fun `test Ctrl-F pages forward and puts the caret on the new first line`() {
    val session = Session(caretLine = 3)
    session.type("<C-F>")
    assertEquals(8, session.top)
    assertEquals(8, session.caretLine)
  }

  @Test
  fun `test Ctrl-B pages back and puts the caret on the new last line`() {
    val session = Session(caretLine = 22)
    session.scrollTo(20)
    session.type("<C-B>")
    assertEquals(12, session.top)
    assertEquals(21, session.caretLine)
  }

  // <C-D> and <C-U> - half a window, with the caret keeping its place in the window.

  @Test
  fun `test Ctrl-D scrolls half a window and takes the caret with it`() {
    val session = Session(caretLine = 3)
    session.type("<C-D>")
    assertEquals(5, session.top)
    assertEquals(8, session.caretLine)
  }

  @Test
  fun `test Ctrl-U scrolls back half a window`() {
    val session = Session(caretLine = 23)
    session.scrollTo(20)
    session.type("<C-U>")
    assertEquals(15, session.top)
    assertEquals(18, session.caretLine)
  }

  @Test
  fun `test a count on Ctrl-D sets the scroll option`() {
    val session = Session(caretLine = 3)
    session.type("3<C-D>")
    assertEquals(3, session.top)
    assertEquals(6, session.caretLine)
    assertEquals(3, injector.options(session.editor).scroll, "a count on <C-D> sets 'scroll'")

    // ...and the next bare <C-D> uses it rather than half a window.
    session.type("<C-D>")
    assertEquals(6, session.top)
    assertEquals(9, session.caretLine)
  }

  @Test
  fun `test Ctrl-D on the last line does nothing`() {
    val session = Session(caretLine = 39)
    session.scrollTo(30)
    session.type("<C-D>")
    assertEquals(30, session.top)
    assertEquals(39, session.caretLine)
  }

  // zt, zz, zb - put this line at the top, the middle or the bottom.

  @Test
  fun `test zt puts the caret line at the top`() {
    val session = Session(caretLine = 20)
    session.scrollTo(15)
    session.type("zt")
    assertEquals(20, session.top)
    assertEquals(20, session.caretLine)
  }

  @Test
  fun `test zb puts the caret line at the bottom`() {
    val session = Session(caretLine = 20)
    session.scrollTo(18)
    session.type("zb")
    assertEquals(11, session.top)
    assertEquals(20, session.caretLine)
  }

  @Test
  fun `test zz centres the caret line`() {
    val session = Session(caretLine = 20)
    session.scrollTo(18)
    session.type("zz")
    assertEquals(16, session.top)
    assertEquals(20, session.caretLine)
  }

  @Test
  fun `test a count on zt names a file line rather than a repetition`() {
    val session = Session(caretLine = 0)
    session.type("25zt")
    assertEquals(24, session.top)
    assertEquals(24, session.caretLine, "the caret follows the line the count named")
  }

  @Test
  fun `test z-CR moves the caret to the first non-blank`() {
    val session = Session(caretLine = 0)
    session.editor.primaryCaret().moveToOffsetNative(session.editor.getLineStartOffset(20) + 3)
    session.type("z<CR>")
    assertEquals(20, session.top)
    assertEquals(session.editor.getLineStartOffset(20), session.editor.primaryCaret().offset)
  }

  // H, M and L - the caret to the top, middle or bottom of what is on screen.

  @Test
  fun `test H moves to the top line on screen`() {
    val session = Session(caretLine = 25)
    session.scrollTo(20)
    session.type("H")
    assertEquals(20, session.caretLine)
    assertEquals(20, session.top, "H moves the caret, not the view")
  }

  @Test
  fun `test L moves to the bottom line on screen`() {
    val session = Session(caretLine = 22)
    session.scrollTo(20)
    session.type("L")
    assertEquals(29, session.caretLine)
  }

  @Test
  fun `test M moves to the middle line on screen`() {
    val session = Session(caretLine = 20)
    session.scrollTo(20)
    session.type("M")
    assertEquals(24, session.caretLine)
  }

  @Test
  fun `test a count on H counts lines in from the top`() {
    val session = Session(caretLine = 25)
    session.scrollTo(20)
    session.type("3H")
    assertEquals(22, session.caretLine)
  }

  @Test
  fun `test scrolloff keeps H away from the very top`() {
    val session = Session(caretLine = 25)
    session.scrollTo(20)
    injector.options(session.editor).scrolloff = 3
    session.type("H")
    assertEquals(23, session.caretLine)
  }

  @Test
  fun `test scrolloff does not apply at the top of the file`() {
    val session = Session(caretLine = 5)
    injector.options(session.editor).scrolloff = 3
    session.type("H")
    assertEquals(0, session.caretLine, "there is nothing above line 0 to keep in view")
  }

  // Sideways, which this host cannot do at all: `visibleRanges` carries no columns.

  @Test
  fun `test zl does not move the view`() {
    val session = Session(caretLine = 5)
    session.type("zl")
    assertEquals(0, session.top)
    assertEquals(5, session.caretLine)
  }
}
