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

/**
 * `:enew`, `:new`, `:vnew`, `:tabnew` and `:tabedit` - the commands that make a place to type in.
 *
 * Vim's new buffer is one with no file behind it, and VS Code's is the untitled file, so `:enew` is
 * one command. The rest are that with something in front: `:new` splits first, `:vnew` splits the
 * other way, `:tabnew` opens a tab - which VS Code, having no tab *pages*, answers with the same
 * untitled file, since an editor opened this way gets a tab of its own either way.
 *
 * Asserted as the sequence of VS Code commands sent, because that sequence is the whole of what
 * these commands do and the order within it is the part that could be wrong: splitting after
 * opening would put the new buffer in the old window.
 */
class NewWindowCommandTest {

  private class Session(text: String = "one two") {
    val fake = FakeEditor(text)
    val commands: MutableList<String> = mutableListOf()
    val arguments: MutableList<Array<Any?>> = mutableListOf()
    val host = VimHost(
      runCommand = { command, args, onDone ->
        commands += command
        arguments += args
        onDone(true)
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    /** The commands sent by [line], with whatever the prompt itself sent thrown away. */
    fun commandsFor(line: String): List<String> {
      commands.clear()
      host.type(fake, ":")
      line.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
      return commands.toList()
    }
  }

  @Test
  fun `test enew asks for an untitled file`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.NEW_UNTITLED_FILE), session.commandsFor("enew"))
  }

  @Test
  fun `test new splits downwards and then opens the new buffer there`() {
    val session = Session()

    assertEquals(
      listOf(VsCodeCommands.SPLIT_EDITOR_DOWN, VsCodeCommands.NEW_UNTITLED_FILE),
      session.commandsFor("new"),
    )
  }

  @Test
  fun `test vnew splits to the right instead`() {
    val session = Session()

    assertEquals(
      listOf(VsCodeCommands.SPLIT_EDITOR_RIGHT, VsCodeCommands.NEW_UNTITLED_FILE),
      session.commandsFor("vnew"),
    )
  }

  /**
   * `:new file` opens the file in the window the split just made.
   *
   * The order is the point: `:split file` in this host still reports that it cannot open a named
   * file, because it asks VS Code to split *with* the name. Splitting first and opening afterwards
   * needs nothing VS Code does not have, since "open" goes to the group that is now focused.
   */
  @Test
  fun `test new with a name splits and then opens that file`() {
    val session = Session()

    assertEquals(
      listOf(VsCodeCommands.SPLIT_EDITOR_DOWN, VsCodeCommands.OPEN),
      session.commandsFor("new /test/other.txt"),
    )
  }

  @Test
  fun `test tabnew asks for an untitled file too`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.NEW_UNTITLED_FILE), session.commandsFor("tabnew"))
  }

  @Test
  fun `test tabedit with a name opens that file`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.OPEN), session.commandsFor("tabedit /test/other.txt"))
  }

  // `:wincmd`, which is `<C-W>` spelled as a command.

  @Test
  fun `test wincmd l focuses the group to the right`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.FOCUS_RIGHT_GROUP), session.commandsFor("wincmd l"))
  }

  @Test
  fun `test wincmd s and v split the way the keys do`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.SPLIT_EDITOR_DOWN), session.commandsFor("wincmd s"))
    assertEquals(listOf(VsCodeCommands.SPLIT_EDITOR_RIGHT), session.commandsFor("wincmd v"))
  }

  @Test
  fun `test wincmd o closes the other groups`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.CLOSE_EDITORS_IN_OTHER_GROUPS), session.commandsFor("wincmd o"))
  }

  /** The count is the ex range, as it is in Vim: `:2wincmd l` is `2<C-W>l`. */
  @Test
  fun `test a count moves that many groups`() {
    val session = Session()

    assertEquals(
      listOf(VsCodeCommands.FOCUS_RIGHT_GROUP, VsCodeCommands.FOCUS_RIGHT_GROUP),
      session.commandsFor("2wincmd l"),
    )
  }

  /**
   * An argument nobody has a `<C-W>` command for does nothing, rather than something else.
   *
   * This is what falls out of implementing `:wincmd` as the keys rather than as a table: `<C-W>z`
   * is not a command, so nothing runs it.
   */
  @Test
  fun `test an argument with no key behind it sends nothing`() {
    val session = Session()

    assertEquals(emptyList(), session.commandsFor("wincmd z"))
  }
}
