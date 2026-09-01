/*
 * Copyright 2003-2026 The IdeaVim authors
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
 * `U` - undo line, which is not `u` and is not an undo stack.
 *
 * The key sweep had this listed as "U needs the same host history undo does", and that was a wrong
 * reading of what it needs: `u` walks a history, `U` walks nothing. Vim keeps one pristine copy of
 * one line, taken the first time that line is touched, and `U` swaps it with what is there now - so
 * a second `U` puts the change back. What it needed was a copy taken *before* an edit, and every
 * edit in this host already funnels through one class.
 */
class UndoLineTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
    val caretOffset: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  @Test
  fun `test U puts back every change made to a line`() {
    val session = Session("one two three\nfour five")
    session.type("x")
    session.type("x")
    session.type("x")
    assertEquals(" two three\nfour five", session.content)

    session.type("U")
    assertEquals("one two three\nfour five", session.content)
  }

  /** "U can be undone with the next U" - the second one puts the change back. */
  @Test
  fun `test a second U toggles back`() {
    val session = Session("one two three")
    session.type("dw")
    session.type("U")
    assertEquals("one two three", session.content)

    session.type("U")
    assertEquals("two three", session.content)
  }

  /** Touching a different line takes a new snapshot, and the old one is gone. */
  @Test
  fun `test moving to another line starts a new snapshot`() {
    val session = Session("one two\nthree four")
    session.type("x")
    session.type("j")
    session.type("x")
    assertEquals("ne two\nhree four", session.content)

    session.type("U")
    assertEquals("ne two\nthree four", session.content, "U should restore the line it was last on")
  }

  /** With nothing saved there is nothing to undo, and Vim beeps rather than changing anything. */
  @Test
  fun `test U with nothing changed does nothing`() {
    val session = Session("one two")
    session.type("U")

    assertEquals("one two", session.content)
  }

  /**
   * A change that spans lines takes no snapshot, mirroring Vim's `u_save`.
   *
   * `u_saveline` is only called for a one-line range, so a join leaves the saved copy alone - and
   * then `U` writes that copy over whatever line 1 now holds, join included. Vim does exactly this,
   * and it is a good part of why `U` surprises people: it does not undo the last change, it
   * restores one remembered line.
   */
  @Test
  fun `test a join leaves the saved line alone and U writes it over the result`() {
    val session = Session("one two\nthree four")
    session.type("x")
    session.type("J")
    assertEquals("ne two three four", session.content)

    session.type("U")
    assertEquals("one two", session.content, "the line saved before the x, written over the join")
  }

  @Test
  fun `test U puts the caret back where the line was first touched`() {
    val session = Session("one two three")
    session.type("wx")
    session.type("U")

    assertEquals(4, session.caretOffset)
  }
}
