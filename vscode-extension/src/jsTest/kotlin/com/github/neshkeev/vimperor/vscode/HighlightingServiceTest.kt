/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.intellij.vim.api.models.Color
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Transaction.addHighlight`, which is how an extension says "this is the text I just acted on".
 *
 * The flash after a yank, the mark on a region waiting to be exchanged: every extension of that
 * kind goes through this one call, and this host answered it with a `TODO` until now.
 *
 * The shape is deliberately not `:match`'s. That one keeps a decoration type per *appearance* and
 * reuses it, because a standing highlight repaints after every keystroke. A highlight here is
 * created once and removed by id - and `setDecorations` replaces every range a type holds, so two
 * highlights sharing a type could not be removed independently. Hence a type each, which is what
 * these tests pin.
 */
class HighlightingServiceTest {

  private class Session(text: String = "one two three") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
      fake.decorations.clear()
    }

    /** Every decoration type that is currently painting something, and what it paints. */
    fun painting(): List<Pair<dynamic, List<Range>>> =
      // `Pair(a, b)` rather than `a to b`: the left side is `dynamic`, and `to` on a dynamic
      // receiver compiles to a JavaScript member call on the decoration type rather than to
      // Kotlin's own infix function.
      fake.decorations.entries.filter { it.value.isNotEmpty() }.map { Pair(it.key.asDynamic(), it.value) }
  }

  @Test
  fun `test a highlight paints the range it was given`() {
    val session = Session()

    injector.highlightingService.addHighlighter(session.editor, 4, 7, Color("#ff000080"), null)

    val painting = session.painting()
    assertEquals(1, painting.size)
    val range = painting.single().second.single()
    assertEquals(0, range.start.line)
    assertEquals(4, range.start.character)
    assertEquals(7, range.end.character)
  }

  /**
   * The colour arrives as CSS, which `#RRGGBBAA` already is - so a colour with alpha needs no
   * conversion. That is the form a highlight-on-yank writes, and the reason the API's `Color`
   * carries a hex string rather than four numbers.
   */
  @Test
  fun `test the colours are handed over as CSS`() {
    val session = Session()

    injector.highlightingService.addHighlighter(session.editor, 0, 3, Color("#ff000080"), Color("#ffffff"))

    val options = session.painting().single().first.options
    assertEquals("#ff000080", options.backgroundColor)
    assertEquals("#ffffff", options.color)
  }

  @Test
  fun `test a colour nobody gave is not invented`() {
    val session = Session()

    injector.highlightingService.addHighlighter(session.editor, 0, 3, Color("#00ff00"), null)

    val options = session.painting().single().first.options
    assertEquals("#00ff00", options.backgroundColor)
    assertNull(options.color, "no foreground was asked for, so none is set")
  }

  @Test
  fun `test removing a highlight takes it off the screen`() {
    val session = Session()
    val id = injector.highlightingService.addHighlighter(session.editor, 0, 3, Color("#00ff00"), null)

    injector.highlightingService.removeHighlighter(session.editor, id)

    assertTrue(session.painting().isEmpty())
  }

  /**
   * The reason for a type each, and the case a shared type would get wrong: two highlights up at
   * once, one removed, the other still showing.
   */
  @Test
  fun `test two highlights are removed independently`() {
    val session = Session()
    val first = injector.highlightingService.addHighlighter(session.editor, 0, 3, Color("#00ff00"), null)
    injector.highlightingService.addHighlighter(session.editor, 4, 7, Color("#0000ff"), null)
    assertEquals(2, session.painting().size, "both are up")

    injector.highlightingService.removeHighlighter(session.editor, first)

    val painting = session.painting()
    assertEquals(1, painting.size, "the other one is still up")
    assertEquals(4, painting.single().second.single().start.character)
  }

  /** A highlight spanning lines is one range, because that is what a decoration takes. */
  @Test
  fun `test a highlight can span lines`() {
    val session = Session("one\ntwo\nthree")

    injector.highlightingService.addHighlighter(session.editor, 2, 9, Color("#00ff00"), null)

    val range = session.painting().single().second.single()
    assertEquals(0, range.start.line)
    assertEquals(2, range.start.character)
    assertEquals(2, range.end.line)
    assertEquals(1, range.end.character)
  }

  /** Removing the same id twice is not an error, which is what the caller's teardown looks like. */
  @Test
  fun `test removing a highlight twice is harmless`() {
    val session = Session()
    val id = injector.highlightingService.addHighlighter(session.editor, 0, 3, Color("#00ff00"), null)

    injector.highlightingService.removeHighlighter(session.editor, id)
    injector.highlightingService.removeHighlighter(session.editor, id)

    assertTrue(session.painting().isEmpty())
  }
}
