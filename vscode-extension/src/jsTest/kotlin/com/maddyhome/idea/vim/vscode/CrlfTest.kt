/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Files that end their lines with `\r\n`, which every other test in this module does not.
 *
 * A sixth shape of blind spot, and the first that is about the *text* rather than about the way a
 * test drives the editor. The five named so far are all about reach - keys but not `:` commands,
 * commands not in the registry, no arguments, no keybinding layer, nothing long enough to
 * accumulate. This one is narrower and worse: every test here, and every one of the 1,025 fixtures
 * harvested from IdeaVim, writes `\n`. A whole class of file was therefore covered by nothing at
 * all, and it is not an exotic one - it is most of a Windows checkout.
 *
 * It is host-specific by construction, which is why IdeaVim's own tests could never have found it.
 * IntelliJ normalises a `Document` to `\n` and applies the file's separator on the way to disk, so
 * `vim-engine` has never seen a carriage return and is not written to. VS Code's `getText()` hands
 * back exactly what the file has. Left alone, the `\r` sits *inside* the line as the engine measures
 * it - and it is the last character of that line, so it is under every motion that goes to the end:
 * `$`, `x` after it, `A`, `J`, `D`, `p` of a linewise register.
 *
 * [DocumentBuffer] normalises on the way in and puts the separator back on the way out, which is
 * IntelliJ's arrangement and the only one the engine can be used with.
 */
class CrlfTest {

  private class Session(text: String, crlf: Boolean = true) {
    val fake = FakeEditor(text).also { if (crlf) it.document.eol = EndOfLine.CRLF }
    val host = VimHost().also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    /** What VS Code holds, line endings and all. */
    val document: String get() = fake.document.content

    /** What the engine holds, which is normalised whatever the file does. */
    val buffer: String get() = editor.buffer.text
  }

  @Test
  fun `test the engine sees a CRLF file with plain newlines`() {
    val session = Session("one\r\ntwo\r\nthree")

    assertEquals("one\ntwo\nthree", session.buffer)
    assertEquals(3, session.editor.lineCount())
  }

  /**
   * `$` on a CRLF line.
   *
   * The end of the line is the `e` of `one`, not the carriage return after it. Without normalising,
   * `getLineEndOffset` counts the `\r` as part of the line and `$` lands on a character that is not
   * on the screen - so `x` deletes it and the file grows a line ending it did not have.
   */
  @Test
  fun `test dollar and x work on the last visible character`() {
    val session = Session("one\r\ntwo")

    session.type("\$x")

    assertEquals("on\r\ntwo", session.document)
  }

  /** `A` appends at the end of the text, not after an invisible carriage return. */
  @Test
  fun `test A appends before the line ending`() {
    val session = Session("one\r\ntwo")

    session.type("A!")
    session.key("<Esc>")

    assertEquals("one!\r\ntwo", session.document)
  }

  /**
   * `J`, which is the one that would have left the evidence lying in the middle of a line.
   *
   * Joining removes the line separator. With a raw `\r\n` buffer the engine takes out the `\n` it
   * knows about and the carriage return stays where it was - in the middle of the joined line.
   */
  @Test
  fun `test J joins without leaving a carriage return behind`() {
    val session = Session("one\r\ntwo")

    session.type("J")

    assertEquals("one two", session.document)
  }

  /** A new line written by the engine gets the file's own ending, which is what a user expects. */
  @Test
  fun `test o opens a line with the file's line ending`() {
    val session = Session("one\r\ntwo")

    session.type("onew")
    session.key("<Esc>")

    assertEquals("one\r\nnew\r\ntwo", session.document)
  }

  /** `dd` takes the whole separator rather than half of it. */
  @Test
  fun `test dd deletes a line and its ending`() {
    val session = Session("one\r\ntwo\r\nthree")

    session.type("jdd")

    assertEquals("one\r\nthree", session.document)
  }

  /**
   * The caret, which is the other half of the conversion.
   *
   * A VS Code position is a line and a character *within the line*, so it says nothing about the
   * separator - which is exactly why the caret path goes through positions rather than through
   * `document.offsetAt`. Reading a click back as a document offset would put the engine's caret one
   * character further off for every line above it.
   */
  @Test
  fun `test a caret set by VS Code is read at the right offset`() {
    val session = Session("one\r\ntwo\r\nthree")
    // Where a click on the `h` of `three` puts it: the third line, fourth character.
    val clicked = FakeSelection(Position(2, 3), Position(2, 3))
    session.fake.selections = arrayOf(clicked)
    session.fake.selection = clicked

    session.editor.syncCaretsFromEditor()

    assertEquals("one\ntwo\nthr".length, session.editor.primaryCaret().offset)
  }

  /** And back the other way: a caret the engine moved is pushed to the line and column it means. */
  @Test
  fun `test a caret moved by the engine is pushed to the right position`() {
    val session = Session("one\r\ntwo\r\nthree")

    session.type("jj\$")
    session.editor.flush()

    assertEquals(2, session.fake.selection.active.line)
    assertEquals(4, session.fake.selection.active.character)
  }

  /**
   * `:w other.txt` keeps the line ending the buffer came with, which is Vim's `'fileformat'`.
   *
   * The write goes to Node rather than through VS Code - `:w` has to report `E212` and a promise
   * cannot answer a command that has already returned - so nothing else is going to put the
   * separator back. Left alone, saving a CRLF file under a new name converts it.
   */
  @Test
  fun `test w to a named file writes the buffer's own line endings`() {
    val session = Session("one\r\ntwo")
    val directory = temporaryDirectory()
    val file = VsCodeFile(HostCommandRunner.None, workspaceRoot = { directory })

    file.createFile("out.txt", VsCodeExecutionContext, session.buffer, session.editor)

    assertEquals("one\r\ntwo", readText("$directory/out.txt"))
  }

  /**
   * And a file read back in is normalised, which is what `:source` and the `.ideavimrc` need.
   *
   * The file that most often has CRLF endings is a Windows user's `_ideavimrc`. A carriage return
   * at the end of every line would land in the right-hand side of every `:map` in it.
   */
  @Test
  fun `test a file read from disk has its line endings normalised`() {
    val path = "${temporaryDirectory()}/ideavimrc"
    writeFile(path, "map x dd\r\nmap y yy\r\n")

    assertEquals("map x dd\nmap y yy\n", NodeFileSystem().readText(path))
  }

  /** An LF file is untouched by any of this, which is the case every other test covers. */
  @Test
  fun `test a file with plain newlines is written back unchanged`() {
    val session = Session("one\ntwo", crlf = false)

    session.type("A!")
    session.key("<Esc>")

    assertEquals("one!\ntwo", session.document)
  }
}
