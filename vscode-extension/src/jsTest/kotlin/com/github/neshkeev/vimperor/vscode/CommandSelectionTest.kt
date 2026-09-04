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
import kotlin.test.assertTrue

/**
 * A selection a host command left behind, which the engine must not adopt.
 *
 * Reported from a real window with `xnoremap = :action editor.action.formatSelection<CR>`: after
 * `V` and `=` the document was formatted correctly and the text stayed selected, with neither
 * `<Esc>` nor `<C-c>` able to clear it.
 *
 * The command finishes with its range still selected, and by then the engine is back in normal mode
 * - `:` leaves visual mode on its way to the command line. Vim has a selection in visual and select
 * modes and nowhere else, so adopting VS Code's left one that nothing could clear: `<Esc>` does not
 * clear a selection in normal mode, because in Vim there is never one there to clear, and every
 * later flush pushed it back.
 */
class CommandSelectionTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val editor: VsCodeEditor get() = host.editorFor(fake) as VsCodeEditor
  }

  /** What the command leaves behind: VS Code selecting its range, the engine in normal mode. */
  private fun Session.leaveASelectionBehind() {
    fake.selections = arrayOf(Selection(Position(0, 0), Position(1, 3)))
    editor.syncCaretsFromEditor()
    editor.dropSelectionLeftByCommand()
  }

  @Test
  fun `test a selection left by a host command does not survive into normal mode`() {
    val session = Session("one\ntwo\nthree\n")

    session.leaveASelectionBehind()

    assertTrue(session.editor.carets().none { it.hasSelection() }, "normal mode must not hold a selection")
    assertTrue(
      session.fake.selections.all { it.anchor.line == it.active.line && it.anchor.character == it.active.character },
      "the drawn selection should have been collapsed rather than left for a key that cannot clear it",
    )
  }

  @Test
  fun `test Esc after it leaves nothing selected`() {
    val session = Session("one\ntwo\nthree\n")
    session.leaveASelectionBehind()

    session.key("<Esc>")

    assertTrue(session.editor.carets().none { it.hasSelection() })
  }

  /**
   * In visual mode the selection *is* the mode, so it is adopted rather than dropped.
   *
   * This is why the drop is not part of `syncCaretsFromEditor`: a drag with the mouse comes through
   * the same sync, and putting the mode check there stopped the mouse from entering visual mode at
   * all. Two existing tests said so before this one existed.
   */
  @Test
  fun `test visual mode still follows VS Code's selection`() {
    val session = Session("one\ntwo\nthree\n")
    session.type("v")

    session.fake.selections = arrayOf(Selection(Position(0, 0), Position(0, 3)))
    session.editor.syncCaretsFromEditor()
    session.editor.dropSelectionLeftByCommand()

    assertTrue(session.editor.carets().any { it.hasSelection() }, "a drag in visual mode is a selection")
  }
}
