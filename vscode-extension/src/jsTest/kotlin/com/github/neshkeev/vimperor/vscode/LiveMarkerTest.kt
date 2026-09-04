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
 * The markers that follow edits, and how many of them there are.
 *
 * This is a performance test, which is unusual here and is the right shape for what went wrong.
 * Every marker in a buffer is moved on every edit, so a marker nobody will read again is not merely
 * memory: it is work added to every keystroke for the rest of the session, and the cost of typing
 * grows with how long the editor has been open. Nothing in a test that presses ten keys would ever
 * notice.
 *
 * Three sources were leaking. Replace mode kept one marker per overwritten character and dropped
 * the stack without dropping them; the engine's backspace *looked up* an entry by building a marker
 * and throwing it away, which in this host meant tracking it for ever; and every caret made one
 * eagerly, while carets are rebuilt from VS Code's selections each time the mouse moves.
 */
class LiveMarkerTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val editor: VsCodeEditor get() = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val markers: Int get() = editor.buffer.markerCount
    val content: String get() = fake.document.content
  }

  /** A caret that never enters insert mode never needs a marker, and the mouse makes many carets. */
  @Test
  fun `test moving about does not accumulate markers`() {
    val session = Session("one two three\nfour five six\nseven eight")
    val before = session.markers

    session.type("wwjbbkw")
    session.key("<Right>")
    session.type("v")
    session.type("e")
    session.key("<Esc>")

    assertEquals(before, session.markers, "motions should not leave markers behind")
  }

  /**
   * Replace mode takes its markers back when the stack goes.
   *
   * One per overwritten character while `R` is running, which is what makes backspace able to put
   * the original back - and nothing to show for it once Escape has been pressed.
   */
  @Test
  fun `test replace mode gives its markers back on escape`() {
    val session = Session("one two three four five")
    val before = session.markers

    session.key("R")
    session.type("abcdefgh")
    assertTrue(session.markers > before, "replace mode should be holding markers while it runs")

    session.key("<Esc>")
    // One left: the marker for where the insert session began, which the caret holds until the next
    // one replaces it. One per caret is the invariant, not zero.
    assertEquals(before + 1, session.markers, "only the caret's own insert marker afterwards")
  }

  /** Backspace in replace mode still restores what it overwrote, which is what the markers are for. */
  @Test
  fun `test backspace in replace mode still restores the original`() {
    val session = Session("one two three")

    session.key("R")
    session.type("XYZ")
    assertEquals("XYZ two three", session.content)
    session.key("<BS>")
    session.key("<BS>")

    assertEquals("Xne two three", session.content)
  }

  /** Insert mode needs one marker per caret and gives it back when the session ends. */
  @Test
  fun `test a long insert session does not accumulate markers`() {
    val session = Session("one")
    val before = session.markers

    repeat(3) {
      session.type("i")
      session.type("abcdefghij")
      session.key("<Esc>")
    }

    // One, not three: each session replaces the previous marker rather than adding to it.
    assertEquals(before + 1, session.markers, "an insert session should replace the last one's marker")
  }
}
