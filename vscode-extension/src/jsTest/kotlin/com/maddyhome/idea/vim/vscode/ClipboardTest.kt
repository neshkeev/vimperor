/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vim's `"+` register, which *is* the system clipboard.
 *
 * The awkward one. VS Code will only talk about the clipboard in promises, and `"+p` is a register
 * read in the middle of a command, so it has to answer now. What it answers with is what the
 * clipboard last said - refreshed when the window regains focus, because a user copying in a
 * browser and switching back is the case that has to work.
 */
class ClipboardTest {

  /** A clipboard whose asynchronous read a test can complete when it chooses. */
  private class DeferredClipboard : SystemClipboard {
    var system: String? = null
    private var mirror: String? = null
    var pendingReads: Int = 0
      private set

    override fun read(): String? = mirror

    override fun write(text: String) {
      mirror = text
      system = text
    }

    override fun refresh() {
      pendingReads++
    }

    /** The promise resolving, which is what a window-focus refresh eventually does. */
    fun completeRefresh() {
      pendingReads = 0
      mirror = system
    }
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val clipboard = DeferredClipboard()
    val host = VimHost(clipboard = clipboard).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  @Test
  fun `test yanking to the plus register reaches the system clipboard`() {
    val session = Session("hello world")
    session.type("\"+yw")

    assertEquals("hello ", session.clipboard.system)
  }

  @Test
  fun `test pasting from the plus register uses the clipboard`() {
    val session = Session("one")
    session.clipboard.system = " and two"
    session.clipboard.completeRefresh()

    session.type("\"+p")

    // `p` puts after the caret, which is on the first character - so this is Vim being right and
    // not the clipboard being wrong.
    assertEquals("o and twone", session.content)
  }

  @Test
  fun `test text copied elsewhere arrives when the window regains focus`() {
    // The workflow this exists for: copy in a browser, switch to VS Code, paste. The refresh
    // happens on focus, so the answer is there before the key is pressed.
    val session = Session("one")
    session.clipboard.system = " and two"

    session.host.refreshClipboard()
    session.clipboard.completeRefresh()
    session.type("\"+p")

    assertEquals("o and twone", session.content)
  }

  @Test
  fun `test text copied elsewhere is not seen until a refresh`() {
    // The known limit, written down: copying in another application *while* VS Code has focus, and
    // pasting without clicking away and back, pastes what the clipboard said before. Making this
    // right means making paste asynchronous, which is a change to the key path.
    val session = Session("one")
    session.type("\"+yiw")
    session.clipboard.system = "copied elsewhere"

    session.type("\"+p")

    assertEquals("oonene", session.content, "the paste should use the copy Vim made, not the newer one")
  }

  @Test
  fun `test the unnamed register does not touch the clipboard`() {
    // Vim only goes near the system clipboard for `"*` and `"+` unless `'clipboard'` says
    // otherwise. A host that wrote every yank through would replace the user's clipboard on `dd`.
    val session = Session("hello world")
    session.type("yw")

    assertEquals(null, session.clipboard.system, "a plain yank should leave the clipboard alone")
  }

  @Test
  fun `test the star register follows the platform`() {
    // Vim's actual rule, which I had backwards when writing this: `"*` is the *selection* only
    // where there is one. On X11 it is the primary selection and differs from `"+`; on macOS and
    // Windows there is one clipboard and the two registers are the same thing.
    //
    // Asserted against the platform rather than hardcoded, because otherwise this test passes on
    // the machine it was written on and fails on the other kind.
    val session = Session("hello world")
    session.type("\"*yw")

    if (injector.systemInfoService.isXWindow) {
      assertEquals(null, session.clipboard.system, "under X11, `\"*` is the primary selection")
    } else {
      assertEquals("hello ", session.clipboard.system, "with one clipboard, `\"*` and `\"+` are the same")
    }
  }
}
