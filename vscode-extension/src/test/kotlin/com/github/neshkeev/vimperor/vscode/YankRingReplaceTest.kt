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

/**
 * `yankring`'s `<C-P>`, which is the reason `runAfterHostCatchesUp` exists.
 *
 * Cycling the last paste through the ring is "undo, then paste something else", and until the seam
 * arrived the second half ran against text the first half had not removed yet - so the extension
 * compiled for this host and was deliberately left out of it.
 *
 * The undo here is the test's, not VS Code's: [Session] restores the text the buffer had before the
 * paste, a tick later, which is the only part of VS Code's undo that this extension depends on.
 * Whether VS Code's own undo stack is right is not this fork's to check; whether the extension
 * survives an undo that finishes after it has returned, is.
 */
class YankRingReplaceTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    private val waiting: MutableList<(Boolean) -> Unit> = mutableListOf()

    /** What the test's undo puts back. Set before the paste that `<C-P>` will undo. */
    var undoTo: String? = null

    val host = VimHost(
      runCommand = { command, _, onDone ->
        // Dispatched now, resolved by [resolve] - which is the whole shape of the problem. VS Code
        // rewrites the document without going through the buffer, and the engine hears about it
        // only when the host re-reads.
        if (command == VsCodeCommands.UNDO) {
          undoTo?.let {
            fake.document.content = it
            fake.document.version = fake.document.version + 1
          }
        }
        waiting += onDone
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      // What a `.ideavimrc` line does: `Plug 'vim-scripts/YankRing.vim'`.
      injector.extensionRegistrator.setOptionByPluginAlias("vim-scripts/YankRing.vim")
    }

    fun resolve() {
      val pending = waiting.toList()
      waiting.clear()
      pending.forEach { it(true) }
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  @Test
  fun `test it is enabled by a Plug line`() {
    Session("alpha beta\n")

    assertEquals(
      listOf("yankring"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
  }

  @Test
  fun `test C-P replaces the last paste with the entry before it`() {
    val session = Session("alpha beta\nX\n")

    session.type("yiw")
    session.type("wyiw")
    session.type("j$")
    session.undoTo = session.content
    session.type("p")
    assertEquals("alpha beta\nXbeta\n", session.content)

    session.key("<C-P>")
    // Nothing yet: the undo is in flight, and the extension has returned without re-pasting.
    assertEquals("alpha beta\nX\n", session.content)

    session.resolve()
    assertEquals("alpha beta\nXalpha\n", session.content)
  }

  @Test
  fun `test C-N walks back the other way`() {
    val session = Session("alpha beta\nX\n")

    session.type("yiw")
    session.type("wyiw")
    session.type("j$")
    session.undoTo = session.content
    session.type("p")

    session.key("<C-P>")
    session.resolve()
    assertEquals("alpha beta\nXalpha\n", session.content)

    session.key("<C-N>")
    session.resolve()
    assertEquals("alpha beta\nXbeta\n", session.content)
  }

  /**
   * The register the user was working with survives, which is the check that the deferred re-paste
   * did not leave the ring entry loaded in the unnamed register.
   *
   * `withUnnamedRegister` puts it back after the block, and the block is now the continuation - so
   * had the save stayed around the *call* it would have restored before the paste it exists for.
   */
  @Test
  fun `test the unnamed register is what it was after cycling`() {
    val session = Session("alpha beta\nX\n")

    session.type("yiw")
    session.type("wyiw")
    session.type("j$")
    session.undoTo = session.content
    session.type("p")

    session.key("<C-P>")
    session.resolve()

    assertEquals("beta", injector.registerGroup.getRegister('"')?.text)
  }
}
