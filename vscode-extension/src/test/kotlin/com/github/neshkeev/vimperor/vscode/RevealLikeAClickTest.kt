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
import com.maddyhome.idea.vim.api.options
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.js.Promise
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

    /** `:set sidescrolloff`, without going through the command line. */
    fun sidescrolloff(columns: Int) {
      injector.options(host.editorFor(fake)).sidescrolloff = columns
    }

    /** Where each cursor command was sent and whether it revealed, as `line:character` or `line:character!`. */
    fun steps(): List<String> =
      fake.cursorMoves.map { "${it.line}:${it.character}" + if (it.reveals) "!" else "" }

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

  // ---- 'sidescrolloff' ---------------------------------------------------------------------------

  /**
   * `:set sidescrolloff=10`, asked for instead of centring: the caret kept ten columns from either
   * edge. The cursor commands reveal a point, and VS Code keeps only the last reveal requested before
   * it paints, so the two sides go a frame apart - the side the caret is heading first, then the
   * other, then the caret itself - each sending the cursor back to the caret without revealing.
   */
  @Test
  fun `test sidescrolloff reveals the columns either side of the caret a frame apart`(): Promise<Unit> {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.clear()

    session.type("j")

    assertEquals(listOf("1:60!", "1:50"), session.steps(), "heading right, so the right-hand margin first")
    return after(200) {
      assertEquals(
        listOf("1:60!", "1:50", "1:40!", "1:50", "1:51", "1:50!"),
        session.steps(),
        "then the left-hand margin, then the caret",
      )
      session.sidescrolloff(0)
    }
  }

  @Test
  fun `test sidescrolloff starts with the left-hand margin when the caret moves left`(): Promise<Unit> {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.clear()

    session.type("h")

    assertEquals(listOf("0:39!", "0:49"), session.steps())
    return after(200) {
      assertEquals(listOf("0:39!", "0:49", "0:59!", "0:49", "0:50", "0:49!"), session.steps())
      session.sidescrolloff(0)
    }
  }

  /** A step worked out for an older caret would send the cursor back to where that caret was. */
  @Test
  fun `test a newer key abandons the steps left from the last one`(): Promise<Unit> {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.clear()

    session.type("j")
    session.type("j")

    return after(200) {
      val lines = session.fake.cursorMoves.map { it.line }
      assertEquals(listOf(1, 1), lines.take(2), "the first j's first step went out at once")
      assertTrue(lines.drop(2).all { it == 2 }, "and nothing for line 1 after the second j: ${session.steps()}")
      session.sidescrolloff(0)
    }
  }

  /** In Insert mode the cursor commands would split typed text into undo steps. */
  @Test
  fun `test entering Insert mode abandons the steps`(): Promise<Unit> {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.clear()

    session.type("j")
    session.type("i")

    return after(200) {
      assertEquals(listOf("1:60!", "1:50"), session.steps())
      session.sidescrolloff(0)
    }
  }

  /** Near the start of the line the left-hand margin is the line's start. */
  @Test
  fun `test the margin stops at the start of the line`() {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("3l")
    session.clear()

    session.type("h")

    assertEquals(listOf("0:0!", "0:2"), session.steps())
    session.sidescrolloff(0)
  }

  /**
   * Vim reads a margin of half the window or more as "keep the caret centred", which needs the window's
   * width. A column further than the window is wide would take the caret off screen for a step, so the
   * margin stops at forty.
   */
  @Test
  fun `test a very wide margin is capped`() {
    val session = Session(longLines)
    session.sidescrolloff(999)
    session.type("100l")
    session.clear()

    session.type("l")

    assertEquals(listOf("0:141!", "0:101"), session.steps())
    session.sidescrolloff(0)
  }

  // ---- zl, zh, zL, zH, zs, ze ------------------------------------------------------------------------

  /** The window by columns, and the caret left where it is - revealing it would scroll straight back. */
  @Test
  fun `test zl and zh scroll the window by a count of columns`() {
    val session = Session(longLines)
    session.type("50l")
    session.clear()

    session.type("zl")
    session.type("3zh")

    assertEquals(listOf(1, -3), session.fake.horizontalScrolls.toList())
    assertEquals(emptyList(), session.moves(), "no reveal to undo the scroll")
    assertEquals(emptyList(), session.fake.reveals.toList(), "nor a padded one")
  }

  /** Half a width nobody can read: the engine's `getApproximateScreenWidth / 2`. */
  @Test
  fun `test zL and zH scroll the window by forty columns`() {
    val session = Session(longLines)
    session.type("50l")
    session.clear()

    session.type("zL")
    session.type("zH")

    assertEquals(listOf(40, -40), session.fake.horizontalScrolls.toList())
    assertEquals(emptyList(), session.moves())
  }

  /** As far right as the window goes, then the caret revealed: it comes in from the left edge. */
  @Test
  fun `test zs scrolls right and brings the caret in at the left edge`() {
    val session = Session(longLines)
    session.type("50l")
    session.clear()

    session.type("zs")

    assertEquals(1, session.fake.horizontalScrolls.size)
    assertTrue(session.fake.horizontalScrolls.single() > 0, "all the way right first")
    assertEquals(listOf("0:51", "0:50!"), session.steps())
  }

  @Test
  fun `test ze scrolls left and brings the caret in at the right edge`() {
    val session = Session(longLines)
    session.type("50l")
    session.clear()

    session.type("ze")

    assertTrue(session.fake.horizontalScrolls.single() < 0, "all the way left first")
    assertEquals(listOf("0:51", "0:50!"), session.steps())
  }

  /** `'sidescrolloff'` columns in from the edge: the column that far out is the one revealed. */
  @Test
  fun `test zs and ze leave sidescrolloff columns at the edge`() {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.clear()

    session.type("zs")
    assertEquals(listOf("0:40!", "0:50"), session.steps())

    session.clear()
    session.type("ze")
    assertEquals(listOf("0:60!", "0:50"), session.steps())
    session.sidescrolloff(0)
  }

  @Test
  fun `test zs on an empty line scrolls all the way left`() {
    val session = Session("$longLine\n\n$longLine")
    session.type("j")
    session.clear()

    session.type("zs")

    assertTrue(session.fake.horizontalScrolls.single() < 0)
    assertEquals(emptyList(), session.moves())
  }

  /** A `'sidescrolloff'` step still waiting from the last motion would bring the window back. */
  @Test
  fun `test zl abandons the sidescrolloff steps still waiting`(): Promise<Unit> {
    val session = Session(longLines)
    session.sidescrolloff(10)
    session.type("50l")
    session.type("zl")
    val sent = session.fake.cursorMoves.size

    return after(200) {
      assertEquals(sent, session.fake.cursorMoves.size, "nothing sent after zl: ${session.steps()}")
      session.sidescrolloff(0)
    }
  }

  private fun after(millis: Int, block: () -> Unit): Promise<Unit> =
    Promise { resolve, reject ->
      setTimeout({
        try {
          block()
          resolve(Unit)
        } catch (e: Throwable) {
          reject(e)
        }
      }, millis)
    }
}

private external fun setTimeout(handler: () -> Unit, timeout: Int): Int
