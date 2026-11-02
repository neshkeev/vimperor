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
import kotlin.test.assertTrue

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
    fun key(notation: String) = host.key(fake, notation)
    val drawn: Selection get() = fake.selections[0]
    val editor: VsCodeEditor get() = host.editorFor(fake)
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

  /**
   * A one-character `v` has to stay exclusive: `active == anchor` collapses the native range and
   * VS Code highlights nothing. Putting `active` at the exclusive end puts the cursor on the
   * *next* character, so `v` at column zero sat on the second character and Visual `0` looked
   * like a miss. Reverse the selection: `active` is the caret, `anchor` is the exclusive end.
   */
  @Test
  fun `test v still highlights the character under the caret`() {
    val session = Session("hello")

    session.type("v")

    assertEquals(0, session.drawn.active.character, "on the character v selected, not the next one")
    assertEquals(1, session.drawn.anchor.character, "exclusive end kept so the character is highlighted")
  }

  /**
   * Reported from a real window: `0` then `v` put the cursor on the second character, so `0` looked
   * like it had missed. Visual `0` after expanding from the start did the same - engine at column
   * zero, `active` one past it.
   */
  @Test
  fun `test Visual 0 draws the cursor at the start of the line`() {
    val session = Session("3. Очистка данных (партиционирование)")

    session.type("vee")
    session.type("0")

    assertEquals(0, session.drawn.active.character, "on the first character, not the second")
    assertTrue(session.drawn.anchor.character > 0, "the exclusive end keeps the character highlighted")
  }

  /** Backward characterwise: `v$` then `0` must move the drawn cursor, not only grow the highlight. */
  @Test
  fun `test Visual 0 from later in the line moves the drawn cursor to column zero`() {
    val session = Session("3. Очистка данных (партиционирование)")

    session.type("ww")
    session.type("ve")
    session.type("0")

    assertEquals(0, session.drawn.active.character, "the cursor follows 0, not the original end")
  }

  /**
   * Characterwise Visual is the same shape as linewise, one column instead of one line. VS Code
   * draws the cursor at `active`, and a characterwise selection ends one past the last selected
   * character, so `ve` put the cursor on the character *after* `e` landed on. `<Esc>` then moved
   * it back onto that character, and the first `w` only walked it forward again - looking like a
   * key that did nothing.
   */
  @Test
  fun `test ve draws the cursor on the last selected character`() {
    val session = Session("hello world")

    session.type("ve")

    assertEquals(4, session.drawn.active.character, "on `o`, not one past it")
    assertEquals(0, session.drawn.anchor.character)
  }

  /** `d` still takes the last selected character, which is the engine's selection, not the drawn one. */
  @Test
  fun `test ve then d deletes the whole word`() {
    val session = Session("hello world")

    session.type("ved")

    assertEquals(" world", session.fake.document.content)
  }

  /**
   * The thing a real window reported: after `<Esc>`, `w` looked like it did nothing, so it took
   * two presses to move a word. The drawn cursor had been sitting on the exclusive end - the
   * character *after* `e` - and `<Esc>` put it back on that character, so the first `w` only
   * walked it forward onto where it had been. On `hello.world` that exclusive end is the dot,
   * which `w` treats as a word of its own.
   */
  @Test
  fun `test Esc from characterwise visual leaves the drawn cursor where it was`() {
    val session = Session("hello.world")

    session.type("ve")
    val at = session.drawn.active.character
    session.key("<Esc>")

    assertEquals(at, session.drawn.active.character, "Esc should not step the cursor back a column")
    session.type("w")
    assertEquals(5, session.drawn.active.character, "w from `o` lands on the dot, one word on")
  }
}
