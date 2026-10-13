/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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

  /**
   * `:help` no longer opens a browser, and these three tests went with it.
   *
   * It used to percent-encode the subject into a search on vimdoc.sourceforge.net, because "there is
   * no help file to open in an IDE". That stopped being true when the commands learnt to describe
   * themselves: `:help` renders Vimperor's own command list and opens it in a buffer - see `HelpTest`
   * and `HelpText`. `gx` above is what still uses the external opener, and it is the reason that seam
   * exists at all.
   */
  @Test
  fun `test help no longer reaches for a browser`() {
    val session = Session("one")
    session.type(":help")
    session.key("<CR>")

    assertNull(session.opener.target, "the help is a buffer now, not a web page")
  }
}
