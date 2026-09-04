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
import kotlin.test.assertTrue

/**
 * What the window is, after a command has changed how much text there is.
 *
 * Reported from a real window: one line of JSON, `V`, then a mapped `=` that reformats it into
 * fifteen. The document came out right and the *view* did not - the first line went up behind the
 * tab bar with most of the window empty below the caret, and `zb` and `gg` then argued from the
 * same wrong numbers.
 *
 * One cause for all three. `visibleRanges` is clipped to the text, so a one-line document reports
 * one visible line however tall the window is, and `screenHeight` is derived from it. That is
 * harmless until the document grows underneath the report: the window still looks one line tall,
 * so putting the caret "on screen" means scrolling its line to the top of a window that could have
 * shown the whole file.
 */
class ViewportAfterAnEditTest {

  private class Session(text: String, height: Int = 40) {
    val fake = FakeEditor(text).also { it.viewportHeight = height }
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      fake.scrolls.clear()
      fake.reveals.clear()
    }

    val editor: VsCodeEditor get() = host.editorFor(fake) as VsCodeEditor
  }

  private fun lines(n: Int) = (0 until n).joinToString("\n") { "line $it" }

  // ---- the report is worthless until VS Code repaints -----------------------------------------

  @Test
  fun `test the viewport is stale until VS Code reports something else`() {
    val session = Session(lines(15))

    session.editor.viewportChangedUnderneath()

    assertTrue(session.editor.viewportIsStale, "nothing has been reported since the document changed")
  }

  @Test
  fun `test a new report clears the staleness`() {
    val session = Session(lines(15))
    session.editor.viewportChangedUnderneath()

    session.fake.topLine = 3

    assertTrue(!session.editor.viewportIsStale, "the editor has repainted and said where it is")
  }

  /** The bogus scroll itself: the caret is corrected onto a window whose height is not known. */
  @Test
  fun `test no scroll is asked for while the viewport is stale`() {
    val session = Session(lines(15))
    session.editor.viewportChangedUnderneath()
    session.fake.scrolls.clear()

    injector.scroll.scrollCaretIntoView(session.editor)

    assertEquals(emptyList(), session.fake.scrolls.toList(), "there is nothing to compute a scroll from")
  }

  /** ...and the caret is not abandoned while that is true. */
  @Test
  fun `test the caret is revealed instead`() {
    val session = Session(lines(15))

    session.editor.viewportChangedUnderneath()

    assertTrue(session.fake.reveals.isNotEmpty(), "VS Code is the one that knows how tall the window is")
  }

  /**
   * Every caret motion in the stale window, not only the edit that made it stale.
   *
   * Skipping the scroll is right and is not enough: `gg` while the report is stale moved the caret
   * to line zero and nothing showed it, which is invisible in a window tall enough to hold the
   * whole document and obvious in an eight-line one with the Output panel open.
   */
  @Test
  fun `test a caret motion while stale is revealed rather than ignored`() {
    val session = Session(lines(15), height = 8)
    val editor = session.editor
    editor.viewportChangedUnderneath()
    session.fake.reveals.clear()
    session.fake.scrolls.clear()

    editor.primaryCaret().moveToOffset(0)
    injector.scroll.scrollCaretIntoView(editor)

    assertEquals(emptyList(), session.fake.scrolls.toList(), "still nothing to compute a scroll from")
    assertTrue(session.fake.reveals.isNotEmpty(), "the caret's line should have been handed to VS Code")
    // More than one is fine and expected - the caret flush reveals too, and a reveal of a position
    // already in view is a no-op. What matters is that every one of them names the caret's line.
    assertTrue(
      session.fake.reveals.all { it.start == 0 && it.end == 0 },
      "every reveal should name the line the caret moved to, got ${session.fake.reveals}",
    )
  }

  // ---- a document that fits has nowhere to scroll ----------------------------------------------

  /**
   * The reported case, once the editor has repainted: fifteen lines, a window that shows all of
   * them, and the caret on the last. Nothing should move.
   */
  @Test
  fun `test a document that fits on screen is never scrolled`() {
    val session = Session(lines(15))
    val editor = session.editor
    editor.primaryCaret().moveToOffset(editor.getLineStartOffset(14))
    session.fake.scrolls.clear()

    injector.scroll.scrollCaretIntoView(editor)

    assertEquals(
      emptyList(),
      session.fake.scrolls.toList(),
      "every line is already visible, so the first one must not go up behind the tab bar",
    )
  }

  // The other side of the guard - that a caret genuinely below the window is still brought back -
  // is `DeferredScrollTest.test a caret off screen is still brought back`, which drives a real
  // keystroke through the scroll machinery rather than calling into it. Asserting it again here
  // would mean rebuilding that setup to say the same thing.
}
