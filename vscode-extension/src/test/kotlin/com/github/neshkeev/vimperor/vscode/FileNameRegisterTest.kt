/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `"%` and `"#`, the file name registers.
 *
 * Neither is stored. Vim makes them read-only and computes them on demand, so the engine asks the
 * host for a name every time one is read - which is the whole reason they need a host test rather
 * than a replayed fixture. IdeaVim's own fixtures for these cannot be replayed here: they turn on
 * `fileName =` support in `VimTestCase` that postdates this fork's snapshot of `src/test`, and on
 * IntelliJ's content root, which is not what this host measures a path against.
 *
 * The workspace folder stands in for Vim's current directory. There is none in a test, so a name
 * here is the absolute path - which is also what Vim shows for a file outside the current
 * directory, so the assertions read the same either way.
 */
class FileNameRegisterTest {

  private class Session(text: String, path: String = "/test/buffer.txt", untitled: Boolean = false) {
    val fake = FakeEditor(text, path, untitled)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
  }

  @Test
  fun `test percent register puts the current file name`() {
    val session = Session("", "/work/notes.md")

    session.type("\"%p")

    assertEquals("/work/notes.md", session.fake.document.content)
  }

  /** Vim's `%` is empty for a buffer with no name, so the register does not exist and `p` is a no-op. */
  @Test
  fun `test percent register is empty for an unnamed buffer`() {
    val session = Session("", "Untitled-1", untitled = true)

    session.type("\"%p")

    assertEquals("", session.fake.document.content)
  }

  /**
   * `#` is the buffer left to get here, which VS Code will not answer: the command behind
   * `selectPreviousTab` goes there without saying where. The host keeps the note itself, in the
   * same place `BufLeave` gets its editor from.
   */
  @Test
  fun `test hash register puts the file left behind`() {
    val session = Session("", "/work/first.md")
    val second = FakeEditor("", "/work/second.md")
    session.host.activeEditorChanged(session.fake)
    session.host.activeEditorChanged(second)

    second.let { KeyHandler.getInstance().fullReset(session.host.editorFor(it)) }
    "\"#p".forEach { session.host.type(second, it.toString()) }

    assertEquals("/work/first.md", second.document.content)
  }

  /** Nothing has been left yet, so there is no alternate file. A whole session may look like this. */
  @Test
  fun `test hash register is empty before any switch`() {
    val session = Session("", "/work/only.md")

    session.type("\"#p")

    assertEquals("", session.fake.document.content)
  }
}
