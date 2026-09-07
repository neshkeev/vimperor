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
 * The caret survives a tab switch, which is what every other editor does without being asked.
 *
 * Reported: put the caret on the third line, switch to another tab, come back, and it is on the
 * first. The host was reading the caret off the `TextEditor` VS Code hands out when a tab is shown
 * again - and VS Code restores that tab's view state, the scroll position and the selection, *after*
 * it reports the editor as active. So the editor said 0,0, the host believed it, and the next flush
 * wrote 0,0 back and made it true.
 */
class TabSwitchCaretTest {

  private val text = "one\ntwo\nthree\nfour\nfive"

  private fun host() = VimHost().also { it.start() }

  @Test
  fun `test the caret stays where it was when a tab is shown again`() {
    val a = FakeEditor(text, path = "/test/a.txt")
    val b = FakeEditor("other\nfile", path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.handle(a, injector.parser.parseKeys("jj"))
    assertEquals(8, host.editorFor(a).primaryCaret().offset, "the third line")

    host.activeEditorChanged(b)
    // A new `TextEditor` for the same document, whose view state VS Code has not restored yet -
    // which is exactly what it hands out, and what the old wrapper was replaced from.
    val shownAgain = FakeEditor(text, path = "/test/a.txt")
    host.activeEditorChanged(shownAgain)

    assertEquals(8, host.editorFor(shownAgain).primaryCaret().offset)
  }

  /** And the editor on screen is corrected too, not just the engine's idea of it. */
  @Test
  fun `test VS Code is told where the caret went`() {
    val a = FakeEditor(text, path = "/test/a.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.handle(a, injector.parser.parseKeys("jj"))

    val shownAgain = FakeEditor(text, path = "/test/a.txt")
    host.activeEditorChanged(shownAgain)

    assertEquals(8, shownAgain.document.offsetAt(shownAgain.selection.active))
  }

  /** A selection is carried over as a selection, not collapsed to its caret. */
  @Test
  fun `test a visual selection survives too`() {
    val a = FakeEditor(text, path = "/test/a.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.handle(a, injector.parser.parseKeys("jvj"))
    val before = host.editorFor(a).primaryCaret().let { it.selectionStart to it.selectionEnd }

    val shownAgain = FakeEditor(text, path = "/test/a.txt")
    host.activeEditorChanged(shownAgain)

    val after = host.editorFor(shownAgain).primaryCaret().let { it.selectionStart to it.selectionEnd }
    assertEquals(before, after)
  }

  /**
   * The same wrapper is reused when VS Code hands back the same object, which is the ordinary case
   * and was never broken - here so that a fix for the other one cannot quietly break it.
   */
  @Test
  fun `test nothing moves when the editor object is the same`() {
    val a = FakeEditor(text, path = "/test/a.txt")
    val b = FakeEditor("other\nfile", path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.handle(a, injector.parser.parseKeys("jj"))

    host.activeEditorChanged(b)
    host.activeEditorChanged(a)

    assertEquals(8, host.editorFor(a).primaryCaret().offset)
    assertEquals(8, a.document.offsetAt(a.selection.active))
  }

  /** A document that shrank while the tab was away cannot leave the caret past the end. */
  @Test
  fun `test a caret past the end of a shorter document is clamped`() {
    val a = FakeEditor(text, path = "/test/a.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.handle(a, injector.parser.parseKeys("G$"))

    val shorter = FakeEditor("one", path = "/test/a.txt")
    host.activeEditorChanged(shorter)

    val offset = host.editorFor(shorter).primaryCaret().offset
    assertEquals(true, offset <= 3, "offset $offset is past the end of a three-character document")
  }
}
