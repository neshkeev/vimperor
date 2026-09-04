/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimProcessGroupBase
import com.maddyhome.idea.vim.api.GlobalOptions
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `:!cmd` and `:{range}!cmd`, which this port did not have and no sweep could say so.
 *
 * That is the part worth writing down. The ex command sweep walks the *engine's* command registry
 * and reports anything that reaches an unbuilt service - but `:!` and `:read` were registered in
 * IdeaVim's IntelliJ module, not in the engine, so from the sweep's point of view they did not
 * exist. A hole it can see is a command that fails; a command that was never there is invisible.
 * Both are in vim-engine now, and [ExCommandsOnlyInIntelliJTest] lists what is still not.
 */
class NodeProcessTest {

  /** A shell that was never run, so a test can say what came back from it. */
  private class FakeShell(
    private val output: String = "",
    private val error: String = "",
    private val exitCode: Int? = 0,
  ) : VimProcessGroupBase() {
    var ranCommand: String? = null
      private set
    var gotInput: String? = null
      private set

    override fun executeCommand(
      editor: VimEditor,
      command: String,
      input: CharSequence?,
      currentDirectoryPath: String?,
      options: GlobalOptions,
    ): String {
      ranCommand = command
      gotInput = input?.toString()
      lastExitCode = exitCode
      return output + error
    }
  }

  private class Session(text: String, val shell: FakeShell) {
    val fake = FakeEditor(text)
    val host = VimHost(processes = shell).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  @Test
  fun `test a range filters those lines through the command`() {
    val shell = FakeShell(output = "one\ntwo\n")
    val session = Session("two\none\nthree", shell)
    session.type(":1,2!sort")
    session.key("<CR>")

    assertEquals("sort", shell.ranCommand)
    assertEquals("two\none\n", shell.gotInput, "the lines in the range are the command's input")
    assertEquals("one\ntwo\nthree", session.content)
  }

  /** Without a range there is nothing to filter: the output goes to the panel, not the buffer. */
  @Test
  fun `test no range leaves the buffer alone`() {
    val shell = FakeShell(output = "hello\n")
    val session = Session("one\ntwo", shell)
    session.type(":!echo hello")
    session.key("<CR>")

    assertEquals("echo hello", shell.ranCommand)
    assertNull(shell.gotInput)
    assertEquals("one\ntwo", session.content)
  }

  /** `!` in the argument repeats the last command, which is Vim's `:!!`. */
  @Test
  fun `test a bang repeats the previous command`() {
    val shell = FakeShell(output = "x\n")
    val session = Session("one", shell)
    session.type(":!first")
    session.key("<CR>")
    session.type(":!!")
    session.key("<CR>")

    assertEquals("first", shell.ranCommand)
  }

  // ---- The Node side, without a shell.

  @Test
  fun `test the shell and its flag come from the Vim options`() {
    var seen: List<String?> = emptyList()
    val group = NodeProcessGroup(
      workspaceRoot = { "/workspace" },
      run = { shell, flag, command, input, directory ->
        seen = listOf(shell, flag, command, input, directory)
        ProcessResult("ok", "", 0)
      },
    )
    val fake = FakeEditor("")
    val host = VimHost().also { it.start() }
    KeyHandler.getInstance().fullReset(host.editorFor(fake))
    // Vim's own configuration rather than the host's: `'shell'` is a Vim option, and a user who
    // sets it in a vimrc expects `:!` to use it.
    ":set shell=/bin/zsh shellcmdflag=-lc".forEach { host.type(fake, it.toString()) }
    host.key(fake, "<CR>")

    val output = group.executeCommand(host.editorFor(fake), "sort", "b\na\n", null, injector.globalOptions())

    assertEquals(listOf("/bin/zsh", "-lc", "sort", "b\na\n", "/workspace"), seen)
    assertEquals("ok", output)
    assertEquals(0, group.lastExitCode)
  }

  /** Both streams, because a command that failed usually said why on stderr and nothing on stdout. */
  @Test
  fun `test stderr is part of the output`() {
    val group = NodeProcessGroup(
      workspaceRoot = { null },
      run = { _, _, _, _, _ -> ProcessResult("out", "and an error", 2) },
    )
    val editor = VimHost().also { it.start() }.editorFor(FakeEditor(""))
    val output = group.executeCommand(editor, "false", null, null, injector.globalOptions())

    assertEquals("outand an error", output)
    assertEquals(2, group.lastExitCode)
  }
}
