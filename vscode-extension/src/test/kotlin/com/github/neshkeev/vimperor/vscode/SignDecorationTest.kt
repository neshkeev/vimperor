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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `:sign` turns into once it reaches VS Code.
 *
 * The engine tests cover the definitions, the placements and the errors. What is left is the part
 * this host had to invent, and it had to invent it because VS Code gives an extension no way to put
 * *text* in the gutter: the sign's one or two characters are drawn into an SVG and handed over as a
 * `data:` URI on `gutterIconPath`. That is the piece nothing else exercises, and the piece where a
 * mistake shows up as an empty gutter rather than as an error.
 */
class SignDecorationTest {

  private class Session(text: String = "one\ntwo\nthree\nfour") {
    val fake = FakeEditor(text)

    /** No `signDisplay` argument, so the real one runs and the stub records what it made. */
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

    fun paintedLines(): List<Int> =
      fake.decorations.values.flatten().map { it.start.line }.sorted()
  }

  @Test
  fun `test a placed sign paints the line it was placed on`() {
    val session = Session()
    session.run("sign define piet text=>>")
    session.run("sign place 1 line=3 name=piet")

    assertEquals(listOf(2), session.paintedLines(), "Vim's line 3 is VS Code's line 2")
  }

  /**
   * The gutter is a picture, because `gutterIconPath` is the only way into that column.
   *
   * The two characters have to survive the round trip into an SVG and a `data:` URI, and `>>` is
   * exactly the text that does not survive being put into markup unescaped - which is why the
   * example in Vim's own documentation is the one used here.
   */
  @Test
  fun `test the sign text arrives as a drawn gutter icon`() {
    val session = Session()
    session.run("sign define piet text=>>")
    session.run("sign place 1 line=1 name=piet")

    val icon = session.painted().gutterIconPath
    assertTrue(icon != null, "a sign with text needs a gutter icon")
    val uri = icon.toString()
    assertTrue("image/svg" in uri, "got: $uri")
    assertTrue("%3E%3E" in uri || "&gt;&gt;" in uri, "the two characters should be in there: $uri")
  }

  @Test
  fun `test linehl colours the whole line`() {
    val session = Session()
    session.run("highlight SignLine guibg=#503030")
    session.run("sign define piet text=>> linehl=SignLine")
    session.run("sign place 1 line=2 name=piet")

    val options = session.painted()
    assertEquals(true, options.isWholeLine)
    assertEquals("#503030", options.backgroundColor)
  }

  /**
   * A group nobody defined leaves the line alone, which is not what `:match` does.
   *
   * A match has to be visible or the command did nothing. A sign whose `linehl` names a group this
   * fork has never heard of has simply not been told to colour anything, and inventing a colour
   * would paint over a file the moment a borrowed config mentioned an unfamiliar group.
   */
  @Test
  fun `test a linehl group nobody defined paints no colour`() {
    val session = Session()
    session.run("sign define piet text=>> linehl=SomeGroupNobodyDefined")
    session.run("sign place 1 line=2 name=piet")

    assertNull(session.painted().backgroundColor)
  }

  @Test
  fun `test unplacing takes the decoration off`() {
    val session = Session()
    session.run("sign define piet text=>>")
    session.run("sign place 1 line=2 name=piet")
    assertEquals(listOf(1), session.paintedLines())

    session.run("sign unplace 1")
    assertEquals(emptyList(), session.paintedLines())
  }

  @Test
  fun `test a sign on a line past the end of the file does not throw`() {
    val session = Session("only one line")
    session.run("sign define piet text=>>")
    session.run("sign place 1 line=99 name=piet")

    assertEquals(listOf(0), session.paintedLines(), "clamped to the last line rather than lost")
  }
}
