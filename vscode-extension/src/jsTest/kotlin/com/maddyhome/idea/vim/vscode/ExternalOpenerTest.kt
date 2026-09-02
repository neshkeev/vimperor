/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimExternalOpener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `gx` and `:help`, which both hand a URL to the operating system.
 *
 * The key sweep never reported this service missing and could not have: it presses `gx` on a buffer
 * with no URL under the caret, so the action returns before it asks for the opener. A hole that
 * needs the right *text* under the caret as well as the right key is invisible to a sweep that only
 * varies the key - which is a third shape of blind spot, after "the sweep only presses keys" and
 * "the command was never in the engine's registry".
 */
class ExternalOpenerTest {

  private class RecordingOpener : VimExternalOpener {
    var target: String? = null
      private set
    var viewer: String? = null
      private set

    override fun open(target: String, viewer: String?) {
      this.target = target
      this.viewer = viewer
    }
  }

  private class Session(text: String) {
    val opener = RecordingOpener()
    val fake = FakeEditor(text)
    val host = VimHost(opener = opener).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  @Test
  fun `test gx opens the url under the caret`() {
    val session = Session("see https://example.com/one for more")
    session.type("w")
    session.type("gx")

    assertEquals("https://example.com/one", session.opener.target)
    assertNull(session.opener.viewer, "with no netrw viewer set, the system handler opens it")
  }

  @Test
  fun `test help opens the online help`() {
    val session = Session("one")
    session.type(":help")
    session.key("<CR>")

    assertEquals("http://vimdoc.sourceforge.net/htmldoc/", session.opener.target)
  }

  /**
   * A topic becomes a search, and a space becomes `+`.
   *
   * Form encoding rather than plain percent encoding, which is the one place the two differ - and
   * it is the difference between `:help i_CTRL-W` and `:help i CTRL-W` reaching the same page.
   */
  @Test
  fun `test a help topic is form encoded into the query`() {
    val session = Session("one")
    session.type(":help i_CTRL-W")
    session.key("<CR>")

    assertEquals(
      "http://vimdoc.sourceforge.net/search.php?docs=help&search=i_CTRL-W",
      session.opener.target,
    )
  }

  @Test
  fun `test a topic with characters a url cannot carry is escaped`() {
    val session = Session("one")
    session.type(":help c_%")
    session.key("<CR>")

    assertEquals(
      "http://vimdoc.sourceforge.net/search.php?docs=help&search=c_%25",
      session.opener.target,
    )
  }
}
