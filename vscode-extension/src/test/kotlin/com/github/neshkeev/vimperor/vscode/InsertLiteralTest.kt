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

    fun ex(command: String) {
      type(":$command")
      key("<CR>")
    }
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

  /**
   * The character `<C-V>` and `<C-K>` produce is inserted as itself.
   *
   * It is handed back through the key handler, and that used to allow mappings, so `inoremap a b`
   * made `<C-V>a` insert `b`. The digits of a code, and the two characters of a digraph, are the
   * argument and were already left alone. The key after the literal is mapped again.
   */
  @Test
  fun `test the character after ctrl-v is not mapped`() {
    val session = Session("")
    session.ex("inoremap a b")
    session.type("i")
    session.key("<C-V>")
    session.type("a")
    session.key("<Esc>")

    assertEquals("a", session.content)
  }

  @Test
  fun `test the digits of a literal code are not mapped`() {
    val session = Session("")
    session.ex("inoremap 6 x")
    session.type("i")
    session.key("<C-V>")
    session.type("065")
    session.key("<Esc>")

    assertEquals("A", session.content)
  }

  @Test
  fun `test mappings apply again after a literal`() {
    val session = Session("")
    session.ex("inoremap a b")
    session.type("i")
    session.key("<C-V>")
    session.type("aa")
    session.key("<Esc>")

    assertEquals("ab", session.content)
  }

  @Test
  fun `test the characters of a digraph are not mapped`() {
    val session = Session("")
    session.ex("inoremap O X")
    session.type("i")
    session.key("<C-K>")
    session.type("OK")
    session.key("<Esc>")

    assertEquals("\u2713", session.content)
  }

  @Test
  fun `test the character a digraph inserts is not mapped`() {
    val session = Session("")
    session.ex("inoremap \u2713 X")
    session.type("i")
    session.key("<C-K>")
    session.type("OK")
    session.key("<Esc>")

    assertEquals("\u2713", session.content)
  }
}
