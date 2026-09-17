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
 * Keeping the caret on screen sideways, which nothing did.
 *
 * Reported from a real window: a line of `aaaaaa ` wider than the editor, `$` at the start of it.
 * The caret went to the end of the line and the window stayed at the beginning, so the caret was
 * simply not visible. VS Code scrolls to its own cursor when *it* moves the cursor; it does not
 * when an extension assigns `selections`, which is what this host does on every keystroke.
 *
 * There is no horizontal viewport to read - `visibleRanges` carries lines and no columns - so a
 * test cannot assert that the window moved. What it can assert is that the caret's position was
 * handed to VS Code to reveal, and that the reveal never names a line that is off screen, which is
 * the property that keeps it from disturbing the vertical scrolling.
 */
class RevealCaretColumnTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      fake.reveals.clear()
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
  }

  private val longLine = "aaaaaa ".repeat(45).trim()

  @Test
  fun `test the caret is revealed after moving to the end of a long line`() {
    val session = Session(longLine)

    session.type("$")

    val reveal = session.fake.reveals.lastOrNull()
    assertTrue(reveal != null, "the caret's position should have been revealed")
    assertEquals(0, reveal.start, "on the line the caret is on")
  }

  /** The point of the guard: a reveal must never name a line the vertical machinery moved past. */
  @Test
  fun `test nothing is revealed when the caret's line is off screen`() {
    val session = Session((0 until 200).joinToString("\n") { "line $it" })
    // The window painted a long way down the file, with the caret about to move far above it.
    session.fake.topLine = 100
    session.fake.viewportHeight = 10
    session.fake.reveals.clear()

    session.type("gg")

    val visible = session.fake.visibleRanges.first()
    assertTrue(
      session.fake.reveals.all { it.start >= visible.start.line && it.end <= visible.end.line },
      "a reveal of an off-screen line would scroll vertically, which is not this function's job",
    )
  }

  /**
   * Reported from a real window: `:set nowrap`, long lines, the mouse scrolls the view to the right,
   * and `j` left the caret off the left-hand edge.
   *
   * A scroll by the user reaches an extension as nothing at all - `visibleRanges` has no columns and
   * there is no event - so the caret in column zero looked as visible as it ever was, and the reveal
   * was skipped because column zero "cannot be off screen". The fake cannot scroll sideways either,
   * so what is asserted is the request: the caret's own line, handed to VS Code to reveal.
   */
  @Test
  fun `test the caret is revealed in the first columns once it is clear of the window's edges`() {
    val session = Session((0 until 40).joinToString("\n") { longLine })
    session.fake.viewportHeight = 30

    session.type("j")

    assertEquals(listOf(1), session.fake.reveals.map { it.start }, "the line `j` moved to")
  }

  /**
   * VS Code pads a reveal by `editor.stickyScroll.maxLineCount` - five lines by default - and by a
   * line more below it, and scrolls a line inside that padding towards the middle. So close to the
   * bottom of the window nothing may be revealed, however few columns the caret is in.
   */
  @Test
  fun `test nothing is revealed within sticky scroll's padding of the bottom edge`() {
    val session = Session((0 until 40).joinToString("\n") { longLine })
    session.fake.viewportHeight = 30
    session.type("24G")
    session.fake.reveals.clear()

    session.type("j")
    assertEquals(listOf(), session.fake.reveals.map { it.start }, "line 24 is five lines above the bottom row, 29")

    session.type("k")
    assertEquals(listOf(23), session.fake.reveals.map { it.start }, "line 23 is six")
  }

  /** The top edge has the same padding, but only once the window is off the first line. */
  @Test
  fun `test nothing is revealed within sticky scroll's padding of the top edge`() {
    val session = Session((0 until 60).joinToString("\n") { longLine })
    session.fake.viewportHeight = 30
    session.fake.topLine = 20
    session.type("27G")
    session.fake.reveals.clear()

    session.type("k")
    assertEquals(listOf(), session.fake.reveals.map { it.start }, "line 25 is five lines under the top row, 20")

    session.type("j")
    assertEquals(listOf(26), session.fake.reveals.map { it.start }, "line 26 is six")
  }

  /** With sticky scroll off and no surrounding lines, the padding is the one line `Default` adds. */
  @Test
  fun `test with sticky scroll off only the edge rows are left alone`() {
    val editor = js("require('vscode').workspace.configuration.editor")
    editor["stickyScroll.enabled"] = false
    try {
      val session = Session((0 until 40).joinToString("\n") { longLine })
      session.fake.viewportHeight = 30
      session.type("29G")
      session.fake.reveals.clear()

      session.type("k")
      assertEquals(listOf(27), session.fake.reveals.map { it.start }, "two lines above the bottom row")
      session.fake.reveals.clear()

      session.type("j")
      assertEquals(listOf(), session.fake.reveals.map { it.start }, "the line above the bottom row")
    } finally {
      js("delete editor['stickyScroll.enabled']")
    }
  }

  /**
   * The regression this guard exists for, from a trace of a real window.
   *
   * `G` with every line of the document already showing revealed the last line, and VS Code
   * scrolled down two to leave room beneath it: `view=[0..15]` became `view=[2..15]`. The window
   * had not changed size, but every scroll command afterwards was computed from a viewport two
   * lines shorter, so `zb` on the last line reported nothing to do while the first line sat above
   * the top of the window.
   */
  @Test
  fun `test a caret in the first screenful of columns is never revealed`() {
    val session = Session((0 until 16).joinToString("\n") { "line $it" })

    session.type("G")

    assertEquals(
      emptyList(),
      session.fake.reveals.toList(),
      "column zero cannot be off the right-hand edge, so nothing should have been asked of the view",
    )
  }

  @Test
  fun `test moving back to the start of the line reveals it too`() {
    val session = Session(longLine)
    session.type("$")
    session.fake.reveals.clear()

    session.type("0")

    assertTrue(
      session.fake.reveals.isNotEmpty(),
      "coming back from the end needs a reveal as much as going out to it did",
    )
  }
}
