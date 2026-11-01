/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What VS Code is left drawing for a linewise selection.
 *
 * Reported from a real window with `{"a":"b"}` on the only line: `V` selected it and put the cursor
 * on the line *below*. A linewise selection ends at the start of the next line - that is what makes
 * `Vd` remove the line rather than empty it - and VS Code draws the cursor at a selection's end.
 *
 * The engine was right; what it asked VS Code to draw was not. So these read the selections handed
 * over rather than the carets, which is the only place the difference is visible.
 */
class DrawnSelectionTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val drawn: Selection get() = fake.selections[0]
    val editor: VsCodeEditor get() = host.editorFor(fake) as VsCodeEditor
  }

  @Test
  fun `test V keeps the drawn cursor on the selected line`() {
    val session = Session("{\"a\":\"b\"}\nsecond\n")

    session.type("V")

    assertEquals(0, session.drawn.active.line, "the cursor should still be on the line V selected")
    assertEquals(0, session.drawn.anchor.line)
  }

  @Test
  fun `test V over two lines draws the cursor on the last of them`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("Vj")

    assertEquals(1, session.drawn.active.line, "on `two`, not on `three`")
  }

  /**
   * The other direction, which is what a real window reported: `V` then `k` left the cursor on
   * the original line, so the selection appeared not to extend. VS Code draws the cursor at
   * `active`, and the drawn selection always put that at the document-order end - the last
   * covered line, which is the right place for `j` and the wrong place for `k`.
   */
  @Test
  fun `test V then k draws the cursor on the line k moved to`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("jVk")

    assertEquals(0, session.drawn.active.line, "on `one`, the line k moved to")
    assertEquals(1, session.drawn.anchor.line, "the original line is the other end")
  }

  /**
   * The shape that made it visible: a long line, `V`, then `k`. The engine's selection grew and
   * the cursor stayed at column 862 of the original line, so on screen nothing had moved.
   */
  @Test
  fun `test V then k on a long line moves the drawn cursor up`() {
    val long = "x".repeat(862)
    val session = Session("short\nmid\n$long\n")

    session.type("jjVk")

    assertEquals(1, session.drawn.active.line, "on `mid`")
    assertEquals(2, session.drawn.anchor.line, "the long line stays the other end")
  }

  /** `d` still takes both lines, which is the engine's selection, not the drawn one. */
  @Test
  fun `test Vk then d deletes both lines`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("jVkd")

    assertEquals("three\n", session.fake.document.content)
  }

  /** The engine's own selection is untouched - only what is drawn moves. */
  @Test
  fun `test the engine still selects the whole line including its newline`() {
    val session = Session("{\"a\":\"b\"}\nsecond\n")

    session.type("V")
    val caret = session.editor.primaryCaret()

    assertEquals(0, caret.selectionStart)
    assertEquals(10, caret.selectionEnd, "past the newline, which is what makes `d` linewise")
  }

  /** The consequence of the line above, said in the terms a user would notice it in. */
  @Test
  fun `test a linewise delete still takes the whole line`() {
    val session = Session("one\ntwo\n")

    session.type("Vd")

    assertEquals("two\n", session.fake.document.content)
  }
}
