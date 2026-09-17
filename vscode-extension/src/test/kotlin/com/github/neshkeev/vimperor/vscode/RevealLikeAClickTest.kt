/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caret brought into view on both axes whenever it moves or the window is scrolled, the way VS
 * Code brings a clicked cursor into view.
 *
 * Reported from a real window with `nowrap`: the mouse scrolled the view to the right, and `j`, `zt`,
 * `zz` or `zb` left the caret off the left-hand edge. VS Code tells an extension nothing about a
 * sideways scroll, and its `revealRange` pads every reveal vertically by sticky scroll's line count,
 * so it could only be used away from the window's edges. `VsCodeEditor.revealLikeAClick` asks for the
 * reveal a click gets instead, through `_moveTo`.
 *
 * The fake cannot scroll sideways, so what is asserted is what was sent: a step one column along and
 * back to the caret, from a source VS Code will not attribute to the user, and no padded reveal.
 */
class RevealLikeAClickTest {

  private class Session(text: String, height: Int = 10, top: Int = 0, active: Boolean = true) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      fake.viewportHeight = height
      fake.topLine = top
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      if (active) js("require('vscode').window").activeTextEditor = fake
      fake.cursorMoves.clear()
      fake.reveals.clear()
    }

    fun type(keys: String) = keys.forEach { host.type(fake, it.toString()) }

    fun key(notation: String) = host.key(fake, notation)

    /** Where each cursor command was sent, as `command line:character`. */
    fun moves(): List<String> = fake.cursorMoves.map { "${it.command} ${it.line}:${it.character}" }

    fun clear() {
      fake.cursorMoves.clear()
      fake.reveals.clear()
      fake.horizontalScrolls.clear()
    }
  }

  @AfterTest
  fun forgetTheActiveEditor() {
    js("require('vscode').window").activeTextEditor = undefined
  }

  private val longLine = "aaaaaa ".repeat(45).trim()
  private val longLines = (0 until 40).joinToString("\n") { longLine }

  @Test
  fun `test j reveals the caret like a click, stepping one column along and back`() {
    val session = Session(longLines)

    session.type("j")

    assertEquals(listOf("_moveTo 1:1", "_moveTo 1:0"), session.moves())
    assertTrue(session.fake.cursorMoves.all { it.source == "vimperor.reveal" }, "a source VS Code gives no kind")
    assertEquals(emptyList(), session.fake.reveals.toList(), "no padded reveal besides")
  }

  /** The case `revealRange` could not do: the window's bottom row, inside sticky scroll's padding. */
  @Test
  fun `test the bottom row is revealed too`() {
    val session = Session(longLines)
    session.type("8G")
    session.clear()

    session.type("j")

    assertEquals(listOf("_moveTo 8:1", "_moveTo 8:0"), session.moves())
    assertEquals(0, session.fake.topLine, "the caret is on screen, so the window stays")
  }

  /**
   * A linewise selection is drawn to the end of its line, where there is no column after the caret,
   * so the step is to the one before.
   */
  @Test
  fun `test the step goes back a column at the end of a line`() {
    val session = Session(longLines)

    session.type("V")

    val end = longLine.length
    assertEquals(listOf("_moveToSelect 0:${end - 1}", "_moveToSelect 0:$end"), session.moves())
  }

  /** `zt`, `zz` and `zb` do not move the caret, and are still a request to look at it. */
  @Test
  fun `test zt zz and zb reveal the caret without moving it`() {
    for (keys in listOf("zt", "zz", "zb")) {
      val session = Session(longLines, top = 5)
      session.type("10G")
      session.clear()

      session.type(keys)

      assertEquals(listOf("_moveTo 9:1", "_moveTo 9:0"), session.moves(), keys)
      assertEquals(emptyList(), session.fake.reveals.toList(), "$keys: no padded reveal to undo the scroll")
    }
  }

  @Test
  fun `test a key that neither moves the caret nor scrolls reveals nothing`() {
    val session = Session(longLines)

    session.type("3")

    assertEquals(emptyList(), session.moves())
  }

  /** A drag keeps the anchor, which is what Visual mode needs from it. */
  @Test
  fun `test Visual mode reveals with the selection kept`() {
    val session = Session(longLines)

    session.type("vj")

    assertTrue(session.moves().isNotEmpty(), "Visual mode is revealed")
    assertTrue(session.fake.cursorMoves.all { it.command == "_moveToSelect" }, session.moves().toString())
    val last = session.fake.cursorMoves.last()
    val active = session.fake.selections.single().active
    assertEquals(active.line to active.character, last.line to last.character, "ending where the selection is drawn to")
  }

  /**
   * An empty line has no column to step to; its caret is in column zero, all the way left.
   *
   * Reported from a real window as `2j` onto an empty line scrolling to the *right*: the column count
   * was large enough that VS Code's `scrollLeft | 0` wrapped the pixel offset it came to. So the count
   * has to stay inside 32 bits at any plausible character width.
   */
  @Test
  fun `test an empty line scrolls all the way left instead`() {
    val session = Session("$longLine\n$longLine\n\n$longLine")

    session.type("2j")

    assertEquals(emptyList(), session.moves())
    val columns = session.fake.horizontalScrolls.single()
    assertTrue(columns < 0, "to the left")
    val widestCharacterInPixels = 50.0
    assertTrue(
      -columns * widestCharacterInPixels < Int.MAX_VALUE.toDouble(),
      "$columns columns wraps VS Code's 32-bit scroll position",
    )
    assertTrue(-columns >= 10_000, "and is still further than a line VS Code renders")
  }

  /** Both commands close VS Code's undo group, so typed text would undo a character at a time. */
  @Test
  fun `test Insert mode does not use it`() {
    val session = Session(longLines)

    session.type("iabc")

    assertEquals(emptyList(), session.moves())
  }

  /** Both commands leave VS Code with one cursor. */
  @Test
  fun `test several carets do not use it`() {
    val session = Session(longLines)

    session.key("<C-V>")
    session.clear()
    session.type("j")

    assertEquals(emptyList(), session.moves())
  }

  /** The commands go to the focused editor, whichever editor the carets belong to. */
  @Test
  fun `test an editor that is not the active one does not use it`() {
    val session = Session(longLines, active = false)

    session.type("j")

    assertEquals(emptyList(), session.moves())
  }
}
