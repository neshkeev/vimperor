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

/**
 * How tall the window is, and the reports that cannot say.
 *
 * VS Code tells an extension which lines it painted and never how many rows it has, so the height
 * every scroll command is computed from is inferred from `visibleRanges` and remembered. Two kinds
 * of report cannot measure it, and the second is what this file is for:
 *
 * - **clipped** to the text, when the view sits past the end of the file. Long known about, a floor
 *   under the height rather than the height, and covered by `VsCodeScrollTest`.
 * - **narrower than the window**, which a real window produces while it has not painted. VS Code
 *   restores a tab's view state *after* it reports the editor as active - `adoptCaretsFrom` has said
 *   so for as long as it has existed - so an activated editor answers from a window it has not laid
 *   out. The range ends before the last line, which is exactly what a clipped report does not do, so
 *   it looks like a measurement.
 *
 * Believing one of those was reported as `zb` not working on the last line. The caret is what tells
 * them apart: a window VS Code is showing has the caret in it, and Vim never leaves the cursor off
 * screen either. See `reportDescribesTheCaret`.
 */
class ScreenHeightTest {

  private val text = (0 until 40).joinToString("\n") { "line $it" }

  private fun host() = VimHost().also { it.start() }

  private fun VimHost.keys(editor: FakeEditor, keys: String) = handle(editor, injector.parser.parseKeys(keys))

  /** An editor with a ten-row window over a forty-line file, registered and reset. */
  private fun session(): Triple<VimHost, FakeEditor, VsCodeEditor> {
    val fake = FakeEditor(text, path = "/test/a.txt")
    fake.viewportHeight = 10
    val host = host()
    val editor = host.editorFor(fake)
    KeyHandler.getInstance().fullReset(editor)
    return Triple(host, fake, editor)
  }

  @Test
  fun `test a report the caret is not in does not lower the remembered height`() {
    val (host, fake, editor) = session()
    assertEquals(10, editor.screenHeight, "an honest report measures the window")

    host.keys(fake, "G")
    // The activation report: a window VS Code has not painted, ending well before the last line so
    // that nothing about it looks clipped - and with the caret nowhere in it.
    fake.topLine = 20
    fake.reportedHeight = 3

    assertEquals(10, editor.screenHeight, "the caret is on line 39, so that is not the window")
  }

  @Test
  fun `test a shorter report with the caret in it is the window laid out smaller`() {
    val (_, fake, editor) = session()
    assertEquals(10, editor.screenHeight)

    // The same shape of report, and this time it can be believed: the caret is in it, so this is
    // what a panel opening under the editor looks like. The guard against fixing the above with a
    // `max` that only ever grows.
    fake.topLine = 0
    fake.reportedHeight = 3

    assertEquals(3, editor.screenHeight, "a window that got shorter has to be believed")
  }

  /**
   * The reported bug, in the shape the trace of it had.
   *
   * The caret is on the last line and the user has dragged that line up the window, so seventeen
   * rows under it are blank; `zb` is meant to put it back on the bottom row. In the trace it did
   * nothing at all - twice - and then worked after `gg` and `G`, which is what named the cause: by
   * then an honest report had been seen and the remembered height was right again.
   */
  @Test
  fun `test zb reaches the bottom row after an activation reported a window it had not painted`() {
    val (host, fake, editor) = session()
    assertEquals(10, editor.screenHeight)
    host.keys(fake, "G")

    // Coming back to the tab, before VS Code has restored its view state: a narrower report that
    // ends well before the last line, and an ordinary keystroke that reads the height off it. This
    // is the report that used to be believed.
    fake.topLine = 20
    fake.reportedHeight = 3
    host.keys(fake, "k")
    fake.reportedHeight = null

    // And now the last line is up in the window, which is where the user had it - so every report
    // from here is clipped to the text and can only ever raise a floor. A height of three would
    // have been permanent, which is why `zb` did nothing twice and then worked.
    fake.topLine = 35
    host.keys(fake, "j")

    host.keys(fake, "zb")

    assertEquals(30, fake.topLine, "the last line belongs on the bottom row of a ten-row window")
    assertEquals(39, editor.primaryCaret().getBufferPosition().line)
  }

  /**
   * The height belongs to the window, not to the `TextEditor` object.
   *
   * VS Code hands out a new one every time a hidden tab is shown, so without carrying this over a
   * replaced wrapper starts from nothing and learns the height from the only report available to
   * it - the one taken before the view state was restored, which is the one that cannot measure it.
   */
  @Test
  fun `test the window's height survives the editor wrapper being replaced`() {
    val (host, fake, editor) = session()
    assertEquals(10, editor.screenHeight)
    host.keys(fake, "G")

    val shownAgain = FakeEditor(text, path = "/test/a.txt")
    shownAgain.viewportHeight = 10
    shownAgain.reportedHeight = 3
    val replacement = host.editorFor(shownAgain)

    assertEquals(10, replacement.screenHeight, "the same tab in the same group is the same window")
  }
}
