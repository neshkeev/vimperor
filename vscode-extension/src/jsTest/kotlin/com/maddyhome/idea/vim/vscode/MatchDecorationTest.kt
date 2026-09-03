/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `:highlight` turns into once it reaches VS Code, which is CSS.
 *
 * [MatchHighlightTest] records the ranges through a stand-in highlighter, because the question
 * there is which text is lit. This one lets the real [VsCodeMatchHighlighter] run and reads the
 * decoration options back out of the stub, because the question here is a different one: a colour
 * the engine resolved has to arrive as something VS Code will actually draw, and every part of
 * that translation - the swap `reverse` performs, the four extra underlines, `guisp` going after
 * the underline rather than onto the text - is host code that nothing else exercises.
 *
 * The other half is the fallback. A group nobody defined must still come out as a `ThemeColor`,
 * because a literal that reads in Dark+ is invisible in Light+, and that path is the one nearly
 * every real `:match` takes.
 */
class MatchDecorationTest {

  private class Session(text: String = "one two one") {
    val fake = FakeEditor(text)

    /** No `matchHighlighter` argument, so the real one runs and the stub records what it made. */
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) {}
        override fun status(text: String?) {}
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun run(command: String) {
      host.key(fake, "<Esc>")
      host.type(fake, ":")
      command.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    /** The options of the one decoration type that is currently painting something. */
    fun painted(): dynamic {
      val showing = fake.decorations.entries.filter { it.value.isNotEmpty() }
      assertEquals(1, showing.size, "exactly one decoration type should be painting, got ${showing.size}")
      return showing.single().key.asDynamic().options
    }

    fun paintedRanges(): Int = fake.decorations.values.sumOf { it.size }
  }

  @Test
  fun `test a group nobody defined is painted in a theme colour`() {
    val session = Session()
    session.run("match Search /one/")

    val options = session.painted()
    assertNotNull(options.backgroundColor, "an undefined group falls back to the theme")
    assertTrue(
      jsTypeOf(options.backgroundColor) == "object",
      "the fallback must be a ThemeColor and not a literal, got ${options.backgroundColor}",
    )
    assertNull(options.color, "the fallback names a background and nothing else")
  }

  @Test
  fun `test a defined group is painted in exactly the colours it was given`() {
    val session = Session()
    session.run("highlight Todo guifg=#ffcc00 guibg=DarkRed")
    session.run("match Todo /one/")

    val options = session.painted()
    assertEquals("#ffcc00", options.color)
    assertEquals("#8b0000", options.backgroundColor)
  }

  @Test
  fun `test bold and italic become the CSS VS Code understands`() {
    val session = Session()
    session.run("highlight Todo gui=bold,italic guifg=Red")
    session.run("match Todo /one/")

    val options = session.painted()
    assertEquals("bold", options.fontWeight)
    assertEquals("italic", options.fontStyle)
  }

  @Test
  fun `test undercurl is a wavy underline and guisp colours it rather than the text`() {
    val session = Session()
    session.run("highlight Todo gui=undercurl guisp=Red")
    session.run("match Todo /one/")

    val options = session.painted()
    assertEquals("underline wavy #ff0000", options.textDecoration)
    assertNull(options.color, "guisp is the underline's colour, not the text's")
  }

  @Test
  fun `test strikethrough rides along with the underline`() {
    val session = Session()
    session.run("highlight Todo gui=underline,strikethrough")
    session.run("match Todo /one/")

    assertEquals("underline line-through", session.painted().textDecoration)
  }

  /** Vim swaps the two colours; both hosts do it by swapping the fields rather than at draw time. */
  @Test
  fun `test reverse swaps the foreground and the background`() {
    val session = Session()
    session.run("highlight Todo guifg=#ffcc00 guibg=#001122 gui=reverse")
    session.run("match Todo /one/")

    val options = session.painted()
    assertEquals("#001122", options.color)
    assertEquals("#ffcc00", options.backgroundColor)
  }

  @Test
  fun `test a group disabled with NONE paints nothing at all`() {
    val session = Session()
    session.run("highlight Todo guibg=Red")
    session.run("match Todo /one/")
    assertEquals(2, session.paintedRanges())

    session.run("highlight Todo NONE")
    assertEquals(0, session.paintedRanges(), "a disabled group takes the highlight off")
  }

  /**
   * Redefining a group while a `:match` is showing.
   *
   * A decoration type carries its appearance, so the new colours need a new type - and the old
   * one has to be emptied, because `setDecorations` only ever replaces the ranges of the type it
   * is handed. Without that the previous colours stay on screen with nothing left to clear them.
   */
  @Test
  fun `test redefining the group repaints without leaving the old colours behind`() {
    val session = Session()
    session.run("highlight Todo guibg=#001122")
    session.run("match Todo /one/")

    session.run("highlight Todo guibg=#334455")
    assertEquals("#334455", session.painted().backgroundColor)
    assertEquals(2, session.paintedRanges(), "only the new type is painting")
  }
}
