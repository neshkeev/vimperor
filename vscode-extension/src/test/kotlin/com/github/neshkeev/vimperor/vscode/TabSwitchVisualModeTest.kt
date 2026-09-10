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
import kotlin.test.assertFalse

/**
 * Visual mode ends in the editor the user leaves, which is Vim's rule and IdeaVim's.
 *
 * Reported from a real window: select lines with `V` in one tab, click another tab, press `j` - and
 * the selection went on growing in the second document. Then `<Esc>` there, back to the first tab,
 * and `<Esc>` again did nothing: the first document's lines stayed selected until a click.
 *
 * Both are one fact about this host. The mode is global - `VimEditorBase.mode` is
 * `injector.vimState.mode` - and the selection is per editor, so a switch left the mode in Visual
 * with its selection in an editor no key was going to. IdeaVim ends Visual mode in the editor being
 * left, in `MotionGroup.fileEditorManagerSelectionChangedCallback`; this host did nothing at all.
 */
class TabSwitchVisualModeTest {

  private val first = "one\ntwo\nthree\nfour\nfive"
  private val second = "alpha\nbeta\ngamma\ndelta"

  private fun host() = VimHost().also { it.start() }

  private fun VimHost.keys(editor: FakeEditor, keys: String) = handle(editor, injector.parser.parseKeys(keys))

  private fun FakeEditor.selected(): Pair<Int, Int> =
    document.offsetAt(selection.anchor) to document.offsetAt(selection.active)

  private fun FakeEditor.hasSelection(): Boolean = selected().let { it.first != it.second }

  @Test
  fun `test j in the other tab moves the caret instead of selecting`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")
    assertEquals("VISUAL LINE", host.modeName())

    host.activeEditorChanged(b)
    assertEquals("NORMAL", host.modeName(), "leaving the editor should have ended Visual mode")
    host.keys(b, "j")

    assertEquals("NORMAL", host.modeName())
    assertEquals(6, host.editorFor(b).primaryCaret().offset, "the caret should be on the second line")
    assertFalse(b.hasSelection(), "nothing should be selected in the second document")
  }

  @Test
  fun `test the editor left behind loses its selection`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")

    host.activeEditorChanged(b)

    assertFalse(a.hasSelection(), "VS Code should have been told the selection went")
    assertFalse(host.editorFor(a).primaryCaret().hasSelection(), "and so should the engine")
  }

  /**
   * The second half of the report. VS Code shows the first tab again as a new `TextEditor` and puts
   * back the selection it had when it was hidden - after reporting it active, and without saying the
   * user did it. The engine no longer holds a selection there, so the next key pushes the caret alone.
   */
  @Test
  fun `test escape back in the first tab clears the selection VS Code restored`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")
    host.activeEditorChanged(b)
    host.keys(b, "j")
    host.key(b, "<Esc>")

    val shownAgain = FakeEditor(first, path = "/test/a.txt")
    host.activeEditorChanged(shownAgain)
    shownAgain.selections = arrayOf(Selection(Position(0, 0), Position(2, 5)))
    host.selectionChanged(shownAgain, kind = null)
    host.key(shownAgain, "<Esc>")

    assertEquals("NORMAL", host.modeName())
    assertFalse(shownAgain.hasSelection(), "the restored selection should be gone")
  }

  /** Ended the way `<Esc>` ends it, so `'<` and `'>` are set and `gv` has something to restore. */
  @Test
  fun `test gv back in the first tab restores the selection`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")
    host.activeEditorChanged(b)
    host.activeEditorChanged(a)

    host.keys(a, "gv")

    assertEquals("VISUAL LINE", host.modeName())
    assertEquals(0 to 13, a.selected())
  }

  /**
   * VS Code can report no active editor on the way to the next one - focus passing through a panel.
   * The editor left behind is the one holding the selection, whatever was active in between.
   */
  @Test
  fun `test a switch by way of no active editor still ends Visual mode`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")

    host.activeEditorChanged(null)
    assertEquals("VISUAL LINE", host.modeName(), "focus in a panel is not leaving the editor")
    host.activeEditorChanged(b)

    assertEquals("NORMAL", host.modeName())
    assertFalse(a.hasSelection())
  }

  /**
   * The same document shown again, or in a split, is not another editor here: the carets belong to
   * the document, so the selection is still the one Visual mode is holding. See `adoptCaretsFrom`.
   */
  @Test
  fun `test the same document shown again stays in Visual mode`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vj")

    host.activeEditorChanged(null)
    val shownAgain = FakeEditor(first, path = "/test/a.txt")
    host.activeEditorChanged(shownAgain)

    assertEquals("VISUAL LINE", host.modeName())
    assertEquals(0 to 7, shownAgain.selected())
  }

  /**
   * A click in the other editor's text, where VS Code may report the selection before the active
   * editor. Following the click into Normal mode used to leave the first editor's selection behind.
   */
  @Test
  fun `test a click in the other editor ends Visual mode in the first`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    host.editorFor(b)
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")

    b.selections = arrayOf(Selection(Position(1, 2), Position(1, 2)))
    val outcome = host.selectionChanged(b, TextEditorSelectionChangeKind.Mouse)

    assertEquals("adopted, and Visual mode ended in the editor left behind", outcome)
    assertEquals("NORMAL", host.modeName())
    assertFalse(a.hasSelection())
  }

  /**
   * A drag in the other editor is selecting there: Visual mode ends in the first editor and starts in
   * the second, which is what the mouse does in a single editor too.
   */
  @Test
  fun `test a drag in the other editor moves Visual mode there`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    host.editorFor(b)
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj")

    b.selections = arrayOf(Selection(Position(1, 0), Position(1, 3)))
    val outcome = host.selectionChanged(b, TextEditorSelectionChangeKind.Mouse)

    assertEquals("adopted, Visual mode ended in the editor left behind, and the mode followed it to VISUAL", outcome)
    assertFalse(a.hasSelection())
    assertEquals(6 to 9, b.selected())
  }

  /**
   * `:` typed from Visual mode, and then a switch. The mode is Command-line there, not Visual, and
   * closing the prompt puts back the mode it was opened from - so leaving the prompt open brought
   * Visual mode back at the next `<Esc>`, with its selection still in the first editor.
   */
  @Test
  fun `test a command line opened from Visual mode is closed by the switch`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vjj:")
    assertEquals("COMMAND", host.modeName())

    host.activeEditorChanged(b)
    assertEquals("NORMAL", host.modeName())
    host.key(b, "<Esc>")
    host.keys(b, "j")

    assertEquals("NORMAL", host.modeName())
    assertFalse(a.hasSelection())
    assertFalse(b.hasSelection())
    assertEquals(6, host.editorFor(b).primaryCaret().offset)
  }

  /** A click in the same editor is still the ordinary case, and says so. */
  @Test
  fun `test a click in the same editor is not reported as leaving it`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    host.editorFor(b)
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "Vj")

    a.selections = arrayOf(Selection(Position(1, 1), Position(1, 1)))

    assertEquals("adopted, and the mode followed it to NORMAL", host.selectionChanged(a, TextEditorSelectionChangeKind.Mouse))
  }

  /**
   * `v` in an empty document, where VS Code is shown an empty selection. Which editor was left is read
   * off where the selection is, so this is the case that rule could have missed: it does not, because
   * the engine's caret still counts as selecting.
   */
  @Test
  fun `test Visual mode in an empty document ends too`() {
    val a = FakeEditor("", path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "v")
    assertEquals("VISUAL", host.modeName())

    host.activeEditorChanged(b)

    assertEquals("NORMAL", host.modeName())
  }

  @Test
  fun `test select mode ends in the editor left behind too`() {
    val a = FakeEditor(first, path = "/test/a.txt")
    val b = FakeEditor(second, path = "/test/b.txt")
    val host = host()
    KeyHandler.getInstance().fullReset(host.editorFor(a))
    host.keys(a, "gh")
    host.key(a, "<S-Right>")
    assertEquals("SELECT", host.modeName())

    host.activeEditorChanged(b)

    assertEquals("NORMAL", host.modeName())
    assertFalse(a.hasSelection())
  }
}
