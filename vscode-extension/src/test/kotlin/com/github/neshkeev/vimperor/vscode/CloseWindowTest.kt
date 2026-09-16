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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `<C-W>q` closes the window, as `<C-W>c` does.
 *
 * IdeaVim mapped only `<C-W>c` until VIM-4324, and this fork's key table came from it, so `<C-W>q`
 * reached no command and the `q` did nothing.
 */
class CloseWindowTest {

  /** The VS Code commands [keys] dispatch, and nothing the host ran while starting. */
  private fun dispatchedFor(keys: String): List<String> {
    val dispatched = mutableListOf<String>()
    val fake = FakeEditor("one\ntwo", path = "/test/close.txt")
    val host = VimHost(runCommand = { command, _, onDone ->
      dispatched += command
      onDone(true)
    }).also { it.start() }
    KeyHandler.getInstance().fullReset(host.editorFor(fake))
    dispatched.clear()
    host.handle(fake, injector.parser.parseKeys(keys))
    return dispatched.toList()
  }

  @Test
  fun `test C-W c closes the window`() {
    assertTrue(dispatchedFor("<C-W>c").isNotEmpty(), "<C-W>c should ask VS Code to close something")
  }

  @Test
  fun `test C-W q closes the window the same way`() {
    assertEquals(dispatchedFor("<C-W>c"), dispatchedFor("<C-W>q"))
  }
}
