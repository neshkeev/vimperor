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
 * Where the `.` mark lands, and the change list that is derived from it.
 *
 * `replaceText` used to set `.` to the offset *after* the change - `start + str.length` - which is
 * right for the `]` end of a range and wrong for a position. `rA` on the first character left `.`
 * at 1, and Vim leaves it at 0.
 *
 * It went unnoticed for as long as it did because the two hosts reach the change list by different
 * routes. IntelliJ feeds its own from the platform's `RecentPlacesListener`, so IdeaVim's `g;` never
 * consulted this mark; a host without such a listener gets Vim's own definition instead - the change
 * list is where `.` has been - and inherits the error once per change. Seven of IdeaVim's replayed
 * fixtures failed on it, all of them `g;` or `g,`.
 */
class LastChangeMarkTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
    val content: String get() = fake.document.content
  }

  @Test
  fun `test the dot mark is on the replaced character`() {
    val session = Session("aaa\nbbb")
    session.type("rX")
    session.type("G")
    session.type("`.")

    assertEquals(0, session.caret, "`rX` changed the first character, so that is where `.` is")
  }

  /**
   * The last inserted character, not the one after it - which is where the caret is left.
   *
   * Insert does not go through `replaceText`, so this one held before the fix as well. It is here to
   * pin the neighbour: the two paths have to agree about what `.` means or `g;` walks a list whose
   * entries mean different things depending on which key made them.
   */
  @Test
  fun `test the dot mark is on the last inserted character`() {
    val session = Session("one\ntwo")
    session.type("ihello")
    session.key("<Esc>")
    session.type("G")
    session.type("`.")

    assertEquals(4, session.caret, "`hello` is offsets 0..4, so `.` is on the `o`")
  }

  /**
   * A deletion has no character to sit on, so the mark goes where the deleted text began.
   *
   * Also unchanged by the fix - `dw` goes through `deleteText` - and also worth pinning, because
   * the clamp in `replaceText` exists for exactly this shape should a caller ever take that route.
   */
  @Test
  fun `test the dot mark after a deletion is where the text was`() {
    val session = Session("one two three")
    session.type("wdw")
    session.type("0")
    session.type("`.")

    assertEquals(4, session.caret)
  }

  // The change list, which on this host is derived from that mark and so carried the same error.

  @Test
  fun `test g_semicolon goes to the change rather than past it`() {
    val session = Session("aaa\nbbb\nccc")
    session.type("rA")
    session.type("G$")
    session.type("g;")

    assertEquals(0, session.caret)
  }

  @Test
  fun `test g_semicolon walks back through the changes`() {
    val session = Session("aaa\nbbb\nccc\nddd")
    session.type("rA")
    session.type("j")
    session.type("rB")
    session.type("j")
    session.type("rC")
    session.type("G$")
    session.type("g;")
    assertEquals(8, session.caret, "the newest change, on the third line")

    session.type("g;")
    assertEquals(4, session.caret)

    session.type("g;")
    assertEquals(0, session.caret)
  }

  /**
   * And a count clamps to the oldest rather than running off the end of the list.
   *
   * This one passed before the fix too: with no motion between the changes and the `999g;`, every
   * entry was one column out together and the oldest was still the oldest.
   */
  @Test
  fun `test a large count stops at the oldest change`() {
    val session = Session("aaa\nbbb\nccc\nddd")
    session.type("rA")
    session.type("j")
    session.type("rB")
    session.type("j")
    session.type("rC")
    session.type("999g;")

    assertEquals(0, session.caret)
    assertEquals("Aaa\nBbb\nCcc\nddd", session.content, "a motion, so nothing was changed by it")
  }
}
