/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:args`, `:argadd`, `:argdelete`, `:bmodified` and `:tabs` - the other names for the one list.
 *
 * Vim keeps three lists apart: the files named on the command line, the buffers it has loaded, and
 * the tab pages. Neither host here does - a file appears once, whichever of the three you ask
 * about - so `:args` and `:ls` print the same files, `:argadd` and `:badd` are the same command,
 * and `:tabfirst` is `:bfirst`. Registering both spellings is the point: one of each pair working
 * and the other reporting `E492` reads as an oversight rather than as a difference between Vim and
 * an editor that has tabs.
 *
 * These run against the stub's tab model, which is what `getBuffers` reads, so the list under test
 * is the real one rather than a fake standing in for it.
 */
class ArgumentListTest {

  /** The stub's tabs are module state, so a test that changed them puts them back. */
  @AfterTest
  fun restoreTabs() {
    openTabs("/one")
  }

  private fun openTabs(vararg paths: String): dynamic =
    window.tabGroups.asDynamic()._openFiles(paths)

  private class Session(text: String = "one two") {
    val fake = FakeEditor(text)
    val channel = OutputRecorder()
    val commands: MutableList<String> = mutableListOf()
    val errors: MutableList<String> = mutableListOf()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      runCommand = { command, _, onDone ->
        commands += command
        onDone(true)
      },
      outputPanel = OutputChannelPanelService(channel),
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

    val printed: String get() = channel.lines.joinToString("\n")
  }

  private class OutputRecorder : OutputChannel {
    val lines: MutableList<String> = mutableListOf()
    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {}
    override fun dispose() {}
  }

  /** Vim's format: the names on one line, the current one in brackets. */
  @Test
  fun `test args prints the list with the current file in brackets`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("args")

    assertEquals("[/test/buffer.txt] /test/other.txt", session.printed.trim())
  }

  @Test
  fun `test argadd opens the file without taking the focus`() {
    openTabs("/test/buffer.txt")
    val session = Session()

    session.run("argadd /test/other.txt")

    assertEquals(listOf(VsCodeCommands.OPEN), session.commands)
    assertEquals(emptyList(), session.errors)
  }

  /** `:badd` is the same command, because the buffer list and the argument list are one list. */
  @Test
  fun `test badd is the same command`() {
    openTabs("/test/buffer.txt")
    val session = Session()

    session.run("badd /test/other.txt")

    assertEquals(listOf(VsCodeCommands.OPEN), session.commands)
  }

  /**
   * `:argdelete other` closes that file - two commands, because VS Code has no "close tab N".
   *
   * The host opens the tab and then closes the active editor, which is how `:bdelete 2` already
   * works here. It is the same `closeFile` either way.
   */
  @Test
  fun `test argdelete closes the file it names`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("argdelete other")

    assertEquals(listOf(VsCodeCommands.OPEN, VsCodeCommands.CLOSE_ACTIVE_EDITOR), session.commands)
  }

  @Test
  fun `test argdelete reports a name nothing matches`() {
    openTabs("/test/buffer.txt")
    val session = Session()

    session.run("argdelete nosuchfile")

    assertTrue(session.errors.any { "E480" in it }, "got ${session.errors}")
  }

  /** Nothing in the stub is dirty, so there is no modified buffer to go to. */
  @Test
  fun `test bmodified reports when nothing has changes`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("bmodified")

    assertTrue(session.errors.any { "E84" in it }, "got ${session.errors}")
  }

  @Test
  fun `test tabs prints a heading per file`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("tabs")

    assertEquals(
      listOf("Tab page 1", ">   /test/buffer.txt", "Tab page 2", "    /test/other.txt"),
      session.printed.trim().split("\n"),
    )
  }

  /** `:tabfirst` and `:tablast` are `:bfirst` and `:blast`, over the same list `:tabs` just printed. */
  @Test
  fun `test the tab ends are the ends of the same list`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    for (command in listOf("tabfirst", "tabrewind", "tablast")) {
      session.errors.clear()
      session.run(command)
      assertEquals(emptyList(), session.errors, "`:$command` should have resolved")
    }
  }

  /** `:sbuffer` is `:split` and then `:buffer`, in that order. */
  @Test
  fun `test sbuffer splits and then goes to the buffer`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("sbuffer 2")

    assertEquals(listOf(VsCodeCommands.SPLIT_EDITOR_DOWN, VsCodeCommands.OPEN), session.commands)
  }

  /** ...and `:vertical sbuffer` splits the other way, without handing the modifier on. */
  @Test
  fun `test vertical sbuffer splits to the right`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session()

    session.run("vertical sbuffer 2")

    assertEquals(listOf(VsCodeCommands.SPLIT_EDITOR_RIGHT, VsCodeCommands.OPEN), session.commands)
  }
}
