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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:pwd`, and the folder it prints.
 *
 * Vim's current directory is a real one that `:cd` moves. A VS Code window has no such thing; it
 * has a workspace folder, and that is already what a relative path is resolved against here - so
 * `:pwd` prints something true rather than something invented, and `:cd` is the command that has to
 * say it cannot. The two halves are asserted separately because they fail separately: the folder
 * can be right while the command is unregistered, and the command can run while answering with a
 * path from somewhere else.
 */
class WorkingDirectoryTest {

  @Test
  fun `test the working directory is the folder open in the window`() {
    val file = VsCodeFile(HostCommandRunner.None, workspaceRoot = { "/home/someone/project" })

    assertEquals("/home/someone/project", file.getWorkingDirectory(VsCodeExecutionContext))
  }

  /** A VS Code window with no folder open is an ordinary thing, not an error. */
  @Test
  fun `test there is no working directory when no folder is open`() {
    val file = VsCodeFile(HostCommandRunner.None, workspaceRoot = { null })

    assertNull(file.getWorkingDirectory(VsCodeExecutionContext))
  }

  /**
   * `:pwd` with nothing open reports `E187`, which is Vim's own error for a directory it cannot
   * name - and the case the tests run in, since nothing opens a folder here.
   */
  @Test
  fun `test pwd reports E187 when there is no folder`() {
    val fake = FakeEditor("one two")
    val errors = mutableListOf<String>()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
    ).also { it.start() }
    KeyHandler.getInstance().fullReset(host.editorFor(fake))

    host.type(fake, ":")
    "pwd".forEach { host.type(fake, it.toString()) }
    host.key(fake, "<CR>")

    assertTrue(errors.any { "E187" in it }, "got $errors")
  }
}
