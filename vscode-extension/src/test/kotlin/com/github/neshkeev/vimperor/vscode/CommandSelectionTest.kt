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

  /**
   * Visual block is one selection per line. Undo restores all of them, and Normal mode keeps the
   * one on the first line of the change. Leaving the rest is a caret on every line, and `<Esc>`
   * has nothing to clear: Normal mode does not drop carets.
   */
  @Test
  fun `test a restored block leaves the caret on its first line`() {
    val session = Session("foo\nbar\nbaz\n")
    // Primary first, the way a downward block is pushed: the last line, then the others.
    session.fake.selections = arrayOf(
      Selection(Position(2, 0), Position(2, 3)),
      Selection(Position(0, 0), Position(0, 3)),
      Selection(Position(1, 0), Position(1, 3)),
    )
    session.editor.syncCaretsFromEditor()

    session.editor.dropSelectionLeftByCommand()

    assertEquals(1, session.editor.carets().size, "normal mode keeps one caret")
    assertEquals(0, session.editor.primaryCaret().offset, "on the first line of the change")
    assertEquals(1, session.fake.selections.size)
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
