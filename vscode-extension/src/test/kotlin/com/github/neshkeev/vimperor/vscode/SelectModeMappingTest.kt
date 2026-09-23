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
 * A mapping made for Visual mode, triggered from Select mode.
 *
 * `:help Select-mode-mapping` is explicit: such a mapping is executed *as if Visual mode was used*,
 * and Select mode is restored afterwards. What made this worth porting from upstream (VIM-4326) is
 * how it fails without it. Select mode replaces the selection with whatever you type, and the
 * mapping's right-hand side is fed back as keys - so `:noremap j d` from Select did not delete the
 * selection, it replaced it with the letter `d` and left the editor in Insert mode. The keys of
 * every Visual mapping were being typed as text.
 *
 * The restore is conditional, and that is the second half: a right-hand side may end the selection,
 * and then there is no Select mode to go back to.
 */
class SelectModeMappingTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
    val mode: String get() = host.modeName()

    fun run(command: String) {
      type(":")
      type(command)
      key("<CR>")
    }

    /** `ve` and `<C-G>`: a word selected, in Select mode, which is where all of this starts. */
    fun selectWord() {
      type("ve")
      key("<C-G>")
    }
  }

  @Test
  fun `test a Visual mapping run from Select mode deletes rather than types`() {
    val session = Session("one two")
    session.run("noremap j d")

    session.selectWord()
    assertEquals("SELECT", session.mode, "the setup is Select mode, or this test asserts nothing")
    session.type("j")

    assertEquals(" two", session.content, "`d` ran as the Visual command it is")
    assertEquals("NORMAL", session.mode, "the selection is gone, so there is no Select mode to restore")
  }

  /** ...and a right-hand side that keeps the selection hands Select mode back. */
  @Test
  fun `test Select mode is restored when the mapping leaves a selection`() {
    val session = Session("one two")
    session.run("noremap j l")

    session.selectWord()
    session.type("j")

    assertEquals("one two", session.content, "`l` is a motion; nothing was typed and nothing replaced")
    assertEquals("SELECT", session.mode)
  }

  /**
   * A mapping the user wrote for Select mode alone stays in Select mode.
   *
   * The guard is on [com.maddyhome.idea.vim.key.MappingInfo.originalModes] - what was written rather
   * than what the mapping ended up covering - so `:snoremap` is untouched. Without that, the one
   * place Vim lets you map Select mode on its own would have been taken away by the fix.
   */
  @Test
  fun `test a Select mode mapping is not run as Visual`() {
    val session = Session("one two")
    session.run("snoremap j d")

    session.selectWord()
    session.type("j")

    assertEquals("d two", session.content, "`:snoremap` means Select mode, where keys are text")
  }
}
