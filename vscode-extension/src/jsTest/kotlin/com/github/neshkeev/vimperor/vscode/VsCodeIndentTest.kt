/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.OptionAccessScope
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

    fun run(line: String) {
      key("<Esc>")
      type(":$line")
      key("<CR>")
    }

    fun option(name: String): Int = injector.optionGroup
      .getOptionValue(injector.optionGroup.getOption(name)!!, OptionAccessScope.EFFECTIVE(host.editorFor(fake)))
      .toVimNumber()
      .value
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

  // ---- What a `~/.vimrc` sets, which used to be accepted and ignored.
  //
  // All three of these are in nearly every Vim config ever written, this host declared all three so
  // that such a config would load without `E518`, and then nothing read them. `set shiftwidth=2`
  // shifted by four. That is the same silent no-op `set nowrap` was, and it is why these exist.

  @Test
  fun `test shiftwidth decides what a shift moves by`() {
    val session = Session("x", width = 4)
    session.run("set shiftwidth=2")
    session.type(">>")

    assertEquals("  x", session.content, "two, because the user said two - not the editor's four")
  }

  @Test
  fun `test a shift follows the editor when nothing has been set`() {
    val session = Session("x", width = 4)
    session.type(">>")

    assertEquals("    x", session.content, "the editor's width, which is what this port has always done")
  }

  /**
   * The other half of that, and the one a mere behaviour test cannot see.
   *
   * A user who sets nothing must be left alone: their editor's indentation is VS Code's business,
   * resolved per language, per file and by detection, and a host writing Vim's defaults over it
   * would re-indent every file anybody opened.
   */
  @Test
  fun `test an editor is not written to when nothing has been set`() {
    val session = Session("x", width = 4)
    session.type(">>")

    assertEquals(0, session.fake.indentWrites)
  }

  @Test
  fun `test expandtab decides whether a Tab is a tab`() {
    val session = Session("", width = 4)
    session.run("set noexpandtab")
    session.type("i")
    session.key("<Tab>")
    session.type("x")
    session.key("<Esc>")

    assertEquals("\tx", session.content)
  }

  @Test
  fun `test tabstop reaches the editor`() {
    val session = Session("x", width = 4)
    session.run("set tabstop=2")

    assertEquals(2, session.fake.indentWidth, "written through TextEditorOptions, where VS Code reads it")
  }

  @Test
  fun `test expandtab reaches the editor`() {
    val session = Session("x", width = 4)
    session.run("set noexpandtab")

    assertEquals(false, session.fake.indentWithSpaces)
  }

  /**
   * `'shiftwidth'` is `indentSize`, which VS Code has kept apart from `tabSize` since 1.85.
   *
   * Before that they were one number, which is why this host conflated them - and why `>>` in a
   * file of eight-column tabs shifted by eight when the user had asked for two.
   */
  @Test
  fun `test shiftwidth reaches the editor as indentSize`() {
    val session = Session("x", width = 4)
    session.run("set shiftwidth=2")

    assertEquals(2, session.fake.indentStep)
  }

  /** And zero goes back to VS Code's own "however wide a tab is", which is Vim's `sw=0`. */
  @Test
  fun `test a shiftwidth of zero defers to the tab width`() {
    val session = Session("x", width = 4)
    session.run("set shiftwidth=2")
    session.run("set shiftwidth=0")
    session.type(">>")

    assertEquals("    x", session.content)
    assertEquals("tabSize", session.fake.indentStep)
  }

  /**
   * A tab character is as wide as `'tabstop'` says whatever `>>` moves by.
   *
   * So one level of a two-column shift is two spaces even in a file written with tabs, because no
   * tab can express column 2 when a tab is eight wide. Vim does this; the old code could not, since
   * it had one number standing for both.
   */
  @Test
  fun `test a shift narrower than a tab is written in spaces`() {
    val session = Session("x", width = 8, spaces = false)
    session.run("set shiftwidth=2")
    session.type(">>")

    assertEquals("  x", session.content)
  }

  /** And a shift that fills a tab is written as one. */
  @Test
  fun `test a shift as wide as a tab is written as a tab`() {
    val session = Session("x", width = 8, spaces = false)
    session.run("set shiftwidth=8")
    session.type(">>")

    assertEquals("\tx", session.content)
  }

  /**
   * `:set tabstop?` answers the editor, not Vim's default of eight.
   *
   * This is what makes the option honest for a user who has set nothing, and it is the thing the
   * accepted group cannot do: there, `:set expandtab?` answers with whatever was last set and the
   * editor may be doing the opposite.
   */
  @Test
  fun `test the options report what the editor is doing`() {
    val session = Session("x", width = 2)

    assertEquals(2, session.option("tabstop"))
    assertEquals(1, session.option("expandtab"), "the fake indents with spaces")
  }
}
