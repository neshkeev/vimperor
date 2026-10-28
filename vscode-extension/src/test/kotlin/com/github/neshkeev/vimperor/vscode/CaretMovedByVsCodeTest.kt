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

/**
 * A caret moved by VS Code rather than by a key, and the three reports that are not that.
 *
 * `TextEditorSelectionChangeKind` is the only thing an event says about who moved the cursor, and it
 * is weaker than its name: VS Code sets it for the keyboard, the mouse and the API, and leaves it
 * **undefined for a cursor command** - `cursorWordLeft` sets the source to `moveWordCommand`, which
 * maps to nothing. This host read the absence as "my own writing coming home" and dropped the event,
 * so every chord `package.json` does not claim moved the caret where Vim could not see it: `Alt+Left`
 * and `Alt+Right` on macOS, `Cmd+Left` and `Cmd+Right`, and anything another extension binds.
 *
 * The report it was found in is a trace of three such moves in Insert mode - column 43 to 37 to 32
 * to 28, a word at a time - and then a typed character, which the engine put at the *stale* offset
 * and then pushed the caret to. Reported as "inserting a symbol moves the caret to the end of line".
 *
 * So the question the host asks is no longer "did the user do this", which VS Code will not answer,
 * but "did I": an edit of this host's in flight, a reveal of this host's, or an echo of what it
 * pushed. What is left over is somebody else's, and in an editor that has the keyboard that is the
 * user.
 */
class CaretMovedByVsCodeTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
    val editor: VsCodeEditor get() = host.editorFor(fake)

    /** What VS Code reports when something other than a key moves the cursor. */
    fun cursorMovedTo(line: Int, character: Int): String {
      val position = Position(line, character)
      fake.selections = arrayOf(Selection(position, position))
      return host.selectionChanged(fake, kind = null)
    }
  }

  @Test
  fun `test a caret moved by a command VS Code will not name is adopted`() {
    val session = Session("one two three")
    session.type("i")

    assertEquals("adopted", session.cursorMovedTo(0, 4))
    assertEquals(4, session.editor.primaryCaret().offset, "the engine's caret should have followed the window's")
  }

  /**
   * The report, end to end but for the window: `Alt+Left` and then a character.
   *
   * The insert is the half that was visible. An engine whose caret is where it last pushed one edits
   * there and then writes that caret back, so the character appeared at the end of the line and the
   * caret jumped after it - two symptoms of one stale offset.
   */
  @Test
  fun `test a character typed after such a move lands where the caret is`() {
    val session = Session("one two three")
    session.type("A")
    session.cursorMovedTo(0, 4)
    session.type("X")

    assertEquals("one Xtwo three", session.content)
    assertEquals(5, session.editor.primaryCaret().offset)
  }

  /**
   * And the mode still follows a selection, which is the other half of what this event is for.
   *
   * A drag reports as the mouse, but a *command* that selects - `cursorWordLeftSelect`, or another
   * extension's - reports as nothing at all, and Vim's answer to a selection appearing is Visual
   * mode however it appeared.
   */
  @Test
  fun `test a selection made by a command VS Code will not name enters Visual mode`() {
    val session = Session("one two three")
    session.fake.selections = arrayOf(Selection(Position(0, 0), Position(0, 3)))

    assertEquals("adopted, and the mode followed it to VISUAL", session.host.selectionChanged(session.fake, kind = null))
  }

  /**
   * The report this host must not read, and the reason the `kind` check was there at all.
   *
   * VS Code keeps its own cursor with the text an edit shifted, and says so before the edit's reply
   * reaches the extension host - so the caret it reports is where the *text* went, which for `O` is
   * the line below the one Vim opened. The push that says otherwise is still in flight.
   *
   * [FakeEditor.holdEdits] is what makes it visible: with the answer given at once the window closes
   * inside `edit`, and no test could look into it.
   */
  @Test
  fun `test an edit of this host's own is not read back as a caret move`() {
    val session = Session("one\ntwo")
    session.type("j")
    session.fake.holdEdits = true
    session.type("O")

    // Read, then released, then asserted. An assertion that throws between the two would leave the
    // edit held and the keystroke half finished, and `KeyHandler` and the engine are global: the
    // first version of this test asserted in the middle and took `PortedExtensionsTest`, the
    // fixture replay and `VsCodeUnimplementedTest` down with it when it failed under ablation.
    val outcome = session.cursorMovedTo(2, 0)
    session.fake.holdEdits = false
    session.fake.releaseEdit()

    assertEquals(
      "ignored, this host's own edit is moving the carets with the text",
      outcome,
      "the cursor VS Code moved to keep it with the text is not a caret move",
    )

    session.type("x")

    assertEquals("one\nx\ntwo", session.content, "O should have left the caret on the line it opened")
  }

  /**
   * And the other one: the step `revealLikeAClick` takes to make VS Code reveal a caret it is already
   * on - one column along and back, because neither cursor command reveals a cursor that has not
   * moved.
   *
   * Adopting it would be more than cosmetic. A caret taken from the window takes the window's column
   * as the one `j` and `k` aim for, so `$` and then `j` would stop following the longest line.
   */
  @Test
  fun `test the step a reveal takes is not read back as a caret move`() {
    val session = Session("one two three\nfour")
    js("require('vscode').window").activeTextEditor = session.fake
    session.type("ll")
    val remembered = session.editor.primaryCaret().vimLastColumn

    assertEquals("ignored, a reveal this host asked for", session.cursorMovedTo(0, 3))
    assertEquals(2, session.editor.primaryCaret().offset, "the engine's caret should not have followed the step")
    assertEquals(remembered, session.editor.primaryCaret().vimLastColumn)
  }

  /**
   * And accounted for only until the next key, which is what gives the record a lifetime rather than
   * a size.
   *
   * A position is not a promise, so a record of having asked for one goes stale into a trap: it
   * silences the user's own move to that column. The stub host caught it - a `zs` in one scenario
   * left an ask for offset 4 and an `Alt+Left` to column 4 three hundred lines later was read as
   * this host's own writing.
   */
  @Test
  fun `test a reveal asked for before the last key is no longer accounted for`() {
    val session = Session("one two three\nfour")
    js("require('vscode').window").activeTextEditor = session.fake
    session.type("ll")
    session.type("i")

    assertEquals("adopted", session.cursorMovedTo(0, 3))
  }

  /**
   * A step is accounted for once. The same position reported again is somebody else putting the caret
   * where a reveal once put it, and a record of having asked must not go on answering for ever.
   */
  @Test
  fun `test a reveal is accounted for once`() {
    val session = Session("one two three\nfour")
    js("require('vscode').window").activeTextEditor = session.fake
    session.type("ll")

    assertEquals("ignored, a reveal this host asked for", session.cursorMovedTo(0, 3))
    assertEquals("adopted", session.cursorMovedTo(0, 3))
  }
}
