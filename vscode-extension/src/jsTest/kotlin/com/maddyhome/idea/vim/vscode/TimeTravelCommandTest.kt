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
import kotlin.test.assertTrue

/**
 * `:earlier` and `:later`, over a history with no branches.
 *
 * Vim's undo is a tree and these two walk it by state count, by file writes or by elapsed time.
 * VS Code's is a line, so `:earlier {N}` is N undos and `:later {N}` is N redos - which is what
 * `:earlier` means whenever nothing has branched. The counted-by-time forms say so rather than
 * guessing: `:earlier 5m` asking for five minutes ago and getting five undos would be a silently
 * wrong answer to a question about time.
 */
class TimeTravelCommandTest {

  private class Session(text: String = "one two") {
    val fake = FakeEditor(text)
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
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun commandsFor(line: String): List<String> {
      commands.clear()
      errors.clear()
      host.type(fake, ":")
      line.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
      return commands.toList()
    }
  }

  @Test
  fun `test earlier undoes once`() {
    val session = Session()

    assertEquals(listOf(VsCodeCommands.UNDO), session.commandsFor("earlier"))
  }

  @Test
  fun `test earlier with a count undoes that many times`() {
    val session = Session()

    assertEquals(List(3) { VsCodeCommands.UNDO }, session.commandsFor("earlier 3"))
  }

  @Test
  fun `test later redoes`() {
    val session = Session()

    assertEquals(List(2) { VsCodeCommands.REDO }, session.commandsFor("later 2"))
  }

  /** The forms this host cannot answer say so instead of doing something else. */
  @Test
  fun `test a count by time is reported rather than guessed at`() {
    val session = Session()

    assertEquals(emptyList(), session.commandsFor("earlier 5m"))
    assertTrue(session.errors.any { "E475" in it }, "got ${session.errors}")
  }
}
