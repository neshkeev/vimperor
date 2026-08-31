/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Indentation, which Vim takes from its own options and this host takes from VS Code's.
 *
 * IdeaVim asks IntelliJ rather than reading `'expandtab'` and `'shiftwidth'`, and the engine has no
 * such options as a result - `createIndentBySize` is a host question by construction. VS Code has
 * the same answer under a different name: `editor.options` carries `tabSize` and `insertSpaces`,
 * already resolved for this file, its language and the user's settings, and already detected from
 * the file's own contents when `detectIndentation` is on.
 *
 * Read on every call rather than cached. The user can change the indentation of an open file from
 * the status bar, and a config read once at open would keep indenting the old way afterwards.
 */
class VsCodeIndentTest {

  private class Session(text: String, spaces: Boolean = true, width: Int = 4) {
    val fake = FakeEditor(text).also { it.indentWithSpaces = spaces; it.indentWidth = width }
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  @Test
  fun `test Tab in insert mode indents by spaces when the file does`() {
    val session = Session("")
    session.type("i")
    session.key("<Tab>")
    session.type("x")
    session.key("<Esc>")

    assertEquals("    x", session.content)
  }

  @Test
  fun `test Tab in insert mode inserts a tab when the file uses tabs`() {
    val session = Session("", spaces = false)
    session.type("i")
    session.key("<Tab>")
    session.type("x")
    session.key("<Esc>")

    assertEquals("\tx", session.content)
  }

  /**
   * A tab stop is a column, not a width. Vim's `'expandtab'` Tab fills up to the next stop, so two
   * characters in with a width of four it inserts two spaces rather than four.
   */
  @Test
  fun `test Tab in insert mode fills up to the next tab stop`() {
    val session = Session("")
    session.type("iab")
    session.key("<Tab>")
    session.type("x")
    session.key("<Esc>")

    assertEquals("ab  x", session.content)
  }

  @Test
  fun `test Tab honours the width the file uses`() {
    val session = Session("", width = 2)
    session.type("i")
    session.key("<Tab>")
    session.type("x")
    session.key("<Esc>")

    assertEquals("  x", session.content)
  }

  /**
   * `S` changes a line and keeps its indent, which is the key that put `createIndentBySize` on the
   * unbuilt list - the only one of Vim's ordinary editing keys still on it.
   */
  @Test
  fun `test S keeps the indent of the line it changes`() {
    val session = Session("    one two\nthree")
    session.type("S")
    session.type("new")
    session.key("<Esc>")

    assertEquals("    new\nthree", session.content)
  }

  @Test
  fun `test S keeps the indent of a line that is not the first`() {
    val session = Session("one\n    two three")
    session.type("j")
    session.type("S")
    session.type("new")
    session.key("<Esc>")

    assertEquals("one\n    new", session.content)
  }

  /**
   * A tab-indented line comes back with spaces, which looks wrong and is what the engine does.
   *
   * The indent is measured as a buffer column, so `\t\t` is two columns rather than eight, and
   * rebuilding two columns gives two spaces however the file is indented. IdeaVim does the same
   * thing for the same reason. Written down as a test because the alternative is finding it again.
   */
  @Test
  fun `test S measures a tab indent in characters, not in columns`() {
    val session = Session("one\n\t\ttwo three", spaces = false, width = 4)
    session.type("j")
    session.type("S")
    session.type("new")
    session.key("<Esc>")

    assertEquals("one\n  new", session.content)
  }
}
