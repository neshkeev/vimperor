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
 * `:retab` against `'expandtab'` and `'tabstop'`, which only exist where a host declares them.
 *
 * The engine's own tests run without either option registered, so they exercise the column
 * arithmetic at the default width and nothing else. Everything a user actually types `:retab` for -
 * turning a file of tabs into a file of spaces after `set expandtab` - depends on reading those two
 * options, and this host is where they are. The lookup is by name, because neither is one of the
 * engine's own: they are things an editor does rather than things Vim does to a buffer, so IdeaVim
 * leaves both to IntelliJ and this host declares them itself.
 */
class RetabTest {

  /**
   * [width] is stated rather than left to chance, and that is a change these tests needed.
   *
   * They used to depend on `'tabstop'` being Vim's default of 8 because nothing set it - which
   * stopped being true when `seedIndent` began starting an editor's `'tabstop'` off at what VS Code
   * says the file's indentation is. Eight is what they meant; the fake now says so.
   */
  private class Session(text: String, width: Int = 8, spaces: Boolean = true) {
    val fake = FakeEditor(text).also { it.indentWidth = width; it.indentWithSpaces = spaces }
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun run(line: String) {
      host.key(fake, "<Esc>")
      host.type(fake, ":")
      line.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    val content: String get() = fake.document.content
  }

  @Test
  fun `test retab turns tabs into spaces once expandtab is on`() {
    val session = Session("ab\tc")
    session.run("set expandtab")
    session.run("retab")

    assertEquals("ab      c", session.content, "the tab reached column 8, so six spaces from column 2")
  }

  /** ...and back again, which is the same command with the option the other way. */
  @Test
  fun `test retab bang turns spaces into tabs once expandtab is off`() {
    val session = Session("ab      c")
    session.run("set noexpandtab")
    session.run("retab!")

    assertEquals("ab\tc", session.content)
  }

  /**
   * With nothing set, the width is the editor's - which is the whole point of seeding it.
   *
   * A VS Code user whose files are laid out at four columns and who types `:retab` means four. Vim's
   * own default of eight is not an answer about their file, it is an answer about a terminal.
   */
  @Test
  fun `test retab uses the width the editor says the file is`() {
    val session = Session("ab\tc", width = 4)
    session.run("set expandtab")
    session.run("retab")

    assertEquals("ab  c", session.content, "the editor says a tab is four wide, so the tab ends at column 4")
  }

  /** `'tabstop'` decides how wide an existing tab is, so the answer changes with it. */
  @Test
  fun `test the tabstop decides how far a tab reaches`() {
    val session = Session("ab\tc")
    session.run("set expandtab")
    session.run("set tabstop=4")
    session.run("retab")

    assertEquals("ab  c", session.content, "at a tabstop of 4 the tab reaches column 4")
  }

  /**
   * Vim sets `'tabstop'` to the width it just laid the file out for.
   *
   * Asserted on the option rather than on the text, because the text cannot tell you: the point of
   * the rewrite is that the line looks the same at the new width as it did at the old one.
   */
  @Test
  fun `test a width argument is left set afterwards`() {
    // A tab-indented file, said out loud. `'expandtab'` is seeded from the editor now, so a fake
    // that indents with spaces makes `:retab` write spaces - which is right, and not this test's
    // subject.
    val session = Session("ab\tc", spaces = false)
    val editor = session.host.editorFor(session.fake)

    session.run("retab 4")

    // The tab reached column 8 at the default tabstop of 8; at 4 that is two tabs.
    assertEquals("ab\t\tc", session.content)
    assertEquals(
      4,
      injector.optionGroup.getOptionValue(VsCodeOptions.tabstop, OptionAccessScope.EFFECTIVE(editor)).value,
    )
  }
}
