/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.GlobalOptions
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimProcessGroupBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:make` and `:grep`, which are the quickfix list with a process in front of it.
 *
 * The list, the parsing and the stepping are engine work and are tested there. What is left is the
 * part that needs a host: `'makeprg'` and `'grepprg'` say which program to run, `$*` in them is
 * where the argument goes, and the process is the same one `:!` runs through. So this asserts the
 * command that was actually built and sent to the shell, with a shell that never runs anything.
 */
class MakeAndGrepTest {

  /** A shell that was never run, so a test can say what was asked of it and what came back. */
  private class FakeShell(private val output: String = "") : VimProcessGroupBase() {
    var ranCommand: String? = null
      private set

    override fun executeCommand(
      editor: VimEditor,
      command: String,
      input: CharSequence?,
      currentDirectoryPath: String?,
      options: GlobalOptions,
    ): String {
      ranCommand = command
      return output
    }
  }

  private class Session(shell: FakeShell) {
    val fake = FakeEditor("one two")
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
      processes = shell,
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun run(line: String) {
      host.key(fake, "<Esc>")
      host.type(fake, ":")
      line.forEach { host.type(fake, it.toString()) }
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

  /** `'makeprg'` has no `$*`, so Vim appends the argument. */
  @Test
  fun `test make runs makeprg with the argument appended`() {
    val shell = FakeShell()
    val session = Session(shell)

    session.run("make test")

    assertEquals("make test", shell.ranCommand)
  }

  @Test
  fun `test makeprg is what gets run`() {
    val shell = FakeShell()
    val session = Session(shell)
    session.run("set makeprg=./gradlew")

    session.run("make build")

    assertEquals("./gradlew build", shell.ranCommand)
  }

  /** `$*` says where the argument goes, which is why `'grepprg'` has one in the middle. */
  @Test
  fun `test the argument goes where the program says`() {
    val shell = FakeShell()
    val session = Session(shell)

    session.run("grep needle")

    assertEquals("grep -n needle /dev/null", shell.ranCommand)
  }

  /** What the program printed becomes the list, and the first entry is opened. */
  @Test
  fun `test the output fills the list and opens the first file`() {
    val shell = FakeShell("src/Main.kt:12:5: error: nope\nsrc/Other.kt:3:1: error: also nope")
    val session = Session(shell)

    session.run("make")

    assertEquals(listOf(VsCodeCommands.OPEN), session.commands)

    session.run("clist")
    assertTrue("src/Main.kt:12 col 5: error: nope" in session.printed, "got ${session.printed}")
    assertTrue("src/Other.kt:3 col 1: error: also nope" in session.printed, "got ${session.printed}")
  }

  /** Nothing that looks like a place is `E42`, and the output is still kept for `:clist`. */
  @Test
  fun `test a build that said nothing useful reports no errors`() {
    val shell = FakeShell("BUILD SUCCESSFUL in 3s")
    val session = Session(shell)

    session.run("make")

    assertEquals(emptyList(), session.commands, "nothing should have been opened")
  }
}
