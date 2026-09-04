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
 * `<C-V>` in Insert mode - a character by its code - and the key that ends the number.
 *
 * These go through [VimHost] rather than the key handler directly, which is the point of them: the
 * engine hands the terminating key back through `postKey` to be replayed after the literal, and the
 * host is what drains that. A test that drove `KeyHandler` itself would never see it.
 *
 * IdeaVim posts that key to Swing's event queue and skips the case entirely under test - "this
 * requires swing, so we can't run it in tests". This host had a `TODO` reading "there is no key
 * queue yet", which was true and a strange thing to have settled for: a queue drained after the
 * current stroke is a few lines, and an event queue was never what the engine wanted, only what
 * IdeaVim had to hand.
 */
class InsertLiteralTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  /** Three digits is as long as a decimal literal gets, so this one needs nothing replayed. */
  @Test
  fun `test a full three digit literal types its character`() {
    val session = Session("")
    session.type("i")
    session.key("<C-V>")
    session.type("065")
    session.key("<Esc>")

    assertEquals("A", session.content)
  }

  @Test
  fun `test the key that ends a short literal is replayed`() {
    val session = Session("")
    session.type("i")
    session.key("<C-V>")
    session.type("65x")
    session.key("<Esc>")

    assertEquals("Ax", session.content)
  }
}
