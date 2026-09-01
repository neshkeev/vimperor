/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Visual mode, which is the first thing the engine and VS Code have to agree about *showing*.
 *
 * Everything so far has been the engine computing and the host writing the result. A selection is
 * different: it exists on screen while the user is still deciding, so the engine's idea of it has to
 * reach VS Code on every keystroke and VS Code's has to come back when the user drags a mouse.
 */
class VsCodeVisualModeTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      // `KeyHandler` is a singleton whose state outlives a test, so one test that ends mid-command
      // makes the next one's keys vanish into it.
      com.maddyhome.idea.vim.KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
    val editor: VsCodeEditor get() = host.editorFor(fake)

    /** The selection VS Code was left holding, as offsets. */
    val selection: Pair<Int, Int>
      get() = fake.document.offsetAt(fake.selection.anchor) to fake.document.offsetAt(fake.selection.active)
  }

  @Test
  fun `test v selects the character under the caret`() {
    val session = Session("one two")
    session.type("v")

    assertEquals("VISUAL", session.host.modeName())
    assertEquals(0 to 1, session.selection, "v should select the character the caret is on")
  }

  @Test
  fun `test a motion extends the selection`() {
    val session = Session("one two")
    session.type("v")
    session.type("e")

    assertEquals(0 to 3, session.selection, "ve should select the word")
  }

  @Test
  fun `test d deletes the selection`() {
    val session = Session("one two")
    session.type("ved")

    assertEquals(" two", session.content)
    assertEquals("NORMAL", session.host.modeName())
  }

  @Test
  fun `test V selects whole lines`() {
    val session = Session("one\ntwo\nthree")
    session.type("V")

    assertEquals("VISUAL LINE", session.host.modeName())
    // Through the newline: a linewise selection covers the line separator too, which is why `Vd`
    // removes the line rather than emptying it.
    assertEquals(0 to 4, session.selection, "V should select the line and its newline")
  }

  @Test
  fun `test Vd deletes the line`() {
    val session = Session("one\ntwo\nthree")
    session.type("Vd")

    assertEquals("two\nthree", session.content)
  }

  @Test
  fun `test Escape leaves visual mode and clears the selection`() {
    val session = Session("one two")
    session.type("ve")
    session.key("<Esc>")

    assertEquals("NORMAL", session.host.modeName())
    assertEquals(session.selection.first, session.selection.second, "the selection should be collapsed")
  }

  @Test
  fun `test c on a selection changes it`() {
    val session = Session("one two")
    session.type("vec")
    session.type("ONE")
    session.key("<Esc>")

    assertEquals("ONE two", session.content)
  }

  @Test
  fun `test dragging a selection with the mouse enters visual mode`() {
    // In Vim a selection *is* visual mode. A host that left the mode alone would show a selection
    // the next keystroke knew nothing about, and `d` would delete one character instead of it.
    val session = Session("one two")
    session.host.editorFor(session.fake)

    session.fake.selections = arrayOf(FakeSelection(Position(0, 0), Position(0, 3)))
    session.host.selectionChanged(session.fake)

    assertEquals("VISUAL", session.host.modeName())

    session.type("d")
    assertEquals(" two", session.content, "d should delete what the mouse selected")
  }

  @Test
  fun `test collapsing the selection leaves visual mode`() {
    val session = Session("one two")
    session.host.editorFor(session.fake)
    session.fake.selections = arrayOf(FakeSelection(Position(0, 0), Position(0, 3)))
    session.host.selectionChanged(session.fake)
    assertEquals("VISUAL", session.host.modeName())

    session.fake.selections = arrayOf(FakeSelection(Position(0, 5), Position(0, 5)))
    session.host.selectionChanged(session.fake)

    assertEquals("NORMAL", session.host.modeName())
  }

  @Test
  fun `test the extension does not read back its own selection`() {
    // Setting selections makes VS Code report a change, and it reports it after the fact rather
    // than during the call. Reading that back would turn every visual motion into a fresh mouse
    // selection, resetting where the selection started.
    val session = Session("one two three")
    session.type("v")
    session.type("e")
    val afterMotion = session.selection

    session.host.selectionChanged(session.fake)

    assertEquals("VISUAL", session.host.modeName())
    assertEquals(afterMotion, session.selection, "the echo should change nothing")
  }

  @Test
  fun `test y on a selection yanks it for p`() {
    val session = Session("one two")
    session.type("vey")
    session.type("$")
    session.type("p")

    assertEquals("one twoone", session.content)
  }
  /**
   * Select mode, which is Visual mode with the keyboard behaving as an ordinary editor's.
   *
   * `gh` enters it, and Vim's rule there is that typing *replaces* what is selected rather than
   * being read as a command. It is the mode IntelliJ's own selection puts IdeaVim into, which is
   * why `exitSelectModeNative` is asked of the editor rather than done by the engine - and it was
   * the last `TODO` any of IdeaVim's fixtures reached.
   */
  @Test
  fun `test gh enters select mode with the character under the caret selected`() {
    val session = Session("one two")
    session.type("gh")

    assertEquals("SELECT", session.host.modeName())
    assertEquals(0 to 1, session.selection)
  }

  @Test
  fun `test typing in select mode replaces the selection`() {
    val session = Session("one two")
    session.type("gh")
    session.type("X")

    assertEquals("Xne two", session.content)
    assertEquals("INSERT", session.host.modeName())
  }

  /**
   * `l` is not a motion in Select mode - it is a letter, and this is what tells the two apart.
   *
   * Select mode extends its selection with the arrow keys, the way any editor does; the ordinary
   * Vim motions are typed text there. That is the entire point of the mode.
   */
  @Test
  fun `test escape leaves select mode and clears the selection`() {
    val session = Session("one two")
    session.type("gh")
    session.key("<S-Right>")
    session.key("<Esc>")

    assertEquals("NORMAL", session.host.modeName())
    assertEquals("one two", session.content)
    assertEquals(session.selection.first, session.selection.second, "the selection should be gone")
  }

  /**
   * Leaving Select mode steps a caret off the end of a line, because Normal mode has nowhere there.
   *
   * This is the `adjustCaret` half of `exitSelectModeNative`, and the only part of it that changes
   * anything a user can see.
   */
  @Test
  fun `test leaving select mode pulls the caret back off the line end`() {
    val session = Session("one\ntwo")
    session.type("gh")
    repeat(3) { session.key("<S-Right>") }
    session.key("<Esc>")

    assertEquals(2, session.editor.primaryCaret().offset, "the caret should be on the last character")
  }
  /**
   * Delete in Select mode, which the engine settles by asking the host.
   *
   * `SelectDeleteBackspaceActionBase` runs whatever the *host* has bound to Delete and only then
   * leaves Select mode, on the grounds that a host might have something else to do with the key.
   * This host had nothing bound, so Delete in Select mode deleted nothing at all - eight of
   * IdeaVim's fixtures said so once the parser could read them.
   */
  @Test
  fun `test delete in select mode removes the selection`() {
    val session = Session("one two")
    session.type("ve")
    session.key("<C-G>")
    session.key("<Del>")

    assertEquals(" two", session.content)
    assertEquals("NORMAL", session.host.modeName())
  }

  @Test
  fun `test backspace in select mode removes the selection`() {
    val session = Session("one two")
    session.type("ve")
    session.key("<C-G>")
    session.key("<BS>")

    assertEquals(" two", session.content)
  }
}
