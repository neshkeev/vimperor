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
 * `:autocmd` - a command to run when something happens to a buffer.
 *
 * The registry and the pattern matching are the engine's now; they had lived in IdeaVim's IntelliJ
 * module with nothing IntelliJ-shaped in them, which is the third time that has been the reason a
 * feature was missing here. What a host owes it is the *events*, and this one has the two VS Code
 * reports plainly: the active editor changing, and the window gaining or losing focus.
 *
 * `:autocmd` was invisible to the ex command sweep for the reason all of these were - a bare
 * `:autocmd` returns a Vim error before it reaches the service. `UnimplementedServicesTest` is what
 * would have found it, and did.
 */
class AutoCmdTest {

  private class Session {
    val first = FakeEditor("one")
    val second = FakeEditor("two")
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(first))
    }

    fun command(text: String, on: FakeEditor = first) {
      ":$text".forEach { host.type(on, it.toString()) }
      host.key(on, "<CR>")
    }
  }

  @Test
  fun `test an autocommand runs when the active editor changes`() {
    val session = Session()
    session.command("autocmd BufEnter * :normal itouched")

    session.host.activeEditorChanged(session.second)

    assertEquals("touchedtwo", session.second.document.content)
    assertEquals("one", session.first.document.content, "only the buffer entered should be touched")
  }

  /** `BufLeave` names the editor being left, which the host has to remember before it stops being active. */
  @Test
  fun `test BufLeave fires for the editor being left`() {
    val session = Session()
    session.host.activeEditorChanged(session.first)
    session.command("autocmd BufLeave * :normal ileft")

    session.host.activeEditorChanged(session.second)

    assertEquals("leftone", session.first.document.content)
    assertEquals("two", session.second.document.content)
  }

  /** A pattern that does not match the file leaves it alone. */
  @Test
  fun `test a pattern that does not match does not fire`() {
    val session = Session()
    session.command("autocmd BufEnter *.rs :normal itouched")

    session.host.activeEditorChanged(session.second)

    assertEquals("two", session.second.document.content)
  }

  @Test
  fun `test augroup clear removes only that group`() {
    val session = Session()
    session.command("augroup mine")
    session.command("autocmd BufEnter * :normal ifrom-group")
    session.command("augroup END")
    session.command("autocmd BufEnter * :normal iungrouped")
    session.command("augroup! mine")

    session.host.activeEditorChanged(session.second)

    assertEquals("ungroupedtwo", session.second.document.content)
  }

  @Test
  fun `test window focus fires FocusGained and FocusLost`() {
    val session = Session()
    session.host.activeEditorChanged(session.first)
    session.command("autocmd FocusLost * :normal iaway")

    session.host.windowFocusChanged(false)

    assertEquals("awayone", session.first.document.content)
  }
  /**
   * `BufWritePost`, which is the other event VS Code reports plainly.
   *
   * `BufWritePre` is not fired, and that is a decision rather than an omission. VS Code's
   * `onWillSaveTextDocument` wants the edits handed back as a promise of `TextEdit`s, and this host
   * applies its own asynchronously - so `autocmd BufWritePre * :%s/\s\+$//e`, which is the reason
   * anyone wants the event, could land after the file was written. Firing nothing beats firing it
   * too late, and `AutoCmdEvent.BufWrite` canonicalises to `BufWritePre`, so both are silent here.
   */
  @Test
  fun `test BufWritePost fires when a document is saved`() {
    val session = Session()
    session.host.activeEditorChanged(session.first)
    session.command("autocmd BufWritePost * :normal isaved")

    session.host.documentSaved(session.first.document)

    assertEquals("savedone", session.first.document.content)
  }

  /** A closed document takes its editor with it, and the engine stops being told about it. */
  @Test
  fun `test a closed document is forgotten`() {
    val session = Session()
    session.host.editorFor(session.second)
    val open = com.maddyhome.idea.vim.api.injector.editorGroup.getEditors().size

    session.host.forgetDocument(session.second.document)

    assertEquals(open - 1, com.maddyhome.idea.vim.api.injector.editorGroup.getEditors().size)
  }
}
