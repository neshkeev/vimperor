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
 * `Cmd+V` in Replace mode, which has to overwrite rather than insert.
 *
 * Vim's Replace mode replaces whatever you type over, and a paste is no exception: `R` and then a
 * paste of `XYZ` puts those three characters *on top of* the next three. VS Code's own paste only
 * inserts, so the editor's chord is claimed for that one mode and the paste runs through the engine
 * instead. Ported from IdeaVim (VIM-1314), which wraps the IDE's paste action for the same reason.
 *
 * One edit rather than one per character, which is why the engine has
 * `replaceTextPastedInReplaceMode` at all: `<C-R>+` inserts the clipboard register in a single
 * write, and that function then turns the inserted span into a replacement and records it in the
 * replace mask - so backspace puts the original text back, which is the half that would be lost by
 * simply deleting what the paste ran over.
 *
 * Three rules, all Vim's. A pasted character replaces one character, the replacing stops at the end
 * of the line rather than eating the line break, and a newline in the pasted text splits the line
 * instead of replacing anything.
 */
class ReplaceModePasteTest {

  private class TestClipboard(private var system: String?) : SystemClipboard {
    private var mirror: String? = null
    override fun read(): String? = mirror
    override fun write(text: String) { mirror = text; system = text }
    override fun refresh(onDone: () -> Unit) { mirror = system; onDone() }
  }

  private class Session(text: String, clipboardHolds: String) {
    val fake = FakeEditor(text)
    val host = VimHost(clipboard = TestClipboard(clipboardHolds)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun paste() = host.pasteInReplaceMode(fake)
    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  @Test
  fun `test a paste in replace mode overwrites what it lands on`() {
    val session = Session("one two three", "XYZ")

    session.type("R")
    session.paste()

    assertEquals("XYZ two three", session.content)
  }

  @Test
  fun `test the caret ends after the pasted text`() {
    val session = Session("one two three", "XYZ")

    session.type("R")
    session.paste()

    assertEquals(3, session.caret)
  }

  @Test
  fun `test replacing stops at the end of the line rather than eating the break`() {
    val session = Session("ab\ncd", "WXYZ")

    session.type("R")
    session.paste()

    assertEquals("WXYZ\ncd", session.content, "the two characters are replaced and the rest appended")
  }

  /**
   * The break itself replaces nothing, and the replacing carries on afterwards on the new line - so
   * `X\nY` over `one two` spends `X` on the `o` and `Y` on the `n`, and the break goes in between.
   */
  @Test
  fun `test a newline in the pasted text splits the line instead of replacing`() {
    val session = Session("one two", "X\nY")

    session.type("R")
    session.paste()

    assertEquals("X\nYe two", session.content)
  }

  @Test
  fun `test backspace puts back what the paste replaced`() {
    val session = Session("one two three", "XYZ")

    session.type("R")
    session.paste()
    session.key("<BS>")
    session.key("<BS>")
    session.key("<BS>")

    assertEquals("one two three", session.content, "the replace mask recorded every pasted character")
  }

  @Test
  fun `test it does nothing outside replace mode`() {
    val session = Session("one two three", "XYZ")

    session.type("i")
    session.paste()

    assertEquals(
      "one two three",
      session.content,
      "insert-mode paste is VS Code's own; the binding is claimed for Replace mode only",
    )
  }
}
