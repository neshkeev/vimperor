/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `<C-V>` - Visual mode over a rectangle rather than a run of text.
 *
 * This is the last large thing on the port's inventory, and the only Vim mode that needs the editor
 * to have more than one caret. The engine's model is IntelliJ's: a block selection is N carets with
 * N one-line selections, rebuilt from scratch on every motion, with one of them designated primary
 * and carrying the block's anchor. VS Code has the same idea under a different name - `selections`
 * is an array and the first entry is the primary - so the model ports; what does not port is the
 * assumption that the primary caret is the first one in the document, because after `<C-V>k` it is
 * the last.
 */
class VsCodeBlockVisualTest {

  private class Session(text: String, caretOffset: Int = 0) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)
      KeyHandler.getInstance().fullReset(editor)
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
    }
  }

  private fun type(text: String, keys: String, caretOffset: Int = 0): String {
    val session = Session(text, caretOffset)
    session.type(keys)
    return session.fake.document.content
  }

  private val three = "one two\nthree four\nfive six"

  // Deleting a rectangle.

  @Test
  fun `test blockwise delete takes a column out of every line`() {
    assertEquals("ne two\nhree four\nive six", type(three, "<C-V>jjd"))
  }

  @Test
  fun `test blockwise delete takes a rectangle out of every line`() {
    assertEquals(" two\nee four\ne six", type(three, "<C-V>jjlld"))
  }

  @Test
  fun `test blockwise delete from the middle of the lines`() {
    assertEquals("on two\nthee four\nfie six", type(three, "<C-V>jjd", caretOffset = 2))
  }

  @Test
  fun `test a block drawn upwards covers the same rectangle`() {
    val session = Session(three, caretOffset = 19)
    session.type("<C-V>kkd")
    assertEquals("ne two\nhree four\nive six", session.fake.document.content)
  }

  // Inserting down the side of one.

  @Test
  fun `test blockwise insert prepends to every line`() {
    assertEquals("Xone two\nXthree four\nXfive six", type(three, "<C-V>jjIX<Esc>"))
  }

  @Test
  fun `test blockwise append adds to the end of every line`() {
    assertEquals("one two;\nthree four;\nfive six;", type(three, "<C-V>jj\$A;<Esc>"))
  }

  @Test
  fun `test blockwise change replaces the rectangle on every line`() {
    assertEquals("X two\nXee four\nXe six", type(three, "<C-V>jjllcX<Esc>"))
  }

  // Ragged edges, which is where a rectangle stops being a rectangle.

  @Test
  fun `test a line too short for the block contributes what it has`() {
    assertEquals("er\n\ner", type("longer\nab\nlonger", "<C-V>jjllld"))
  }

  // The carets themselves, which is what makes this different from every other mode.

  @Test
  fun `test the block reaches VS Code as one selection per line`() {
    val session = Session(three)
    session.type("<C-V>jjll")
    assertEquals(3, session.fake.selections.size)
  }

  @Test
  fun `test the primary caret is the corner the motion moved`() {
    val session = Session(three)
    session.type("<C-V>jj")
    assertEquals(2, session.editor.primaryCaret().getBufferPosition().line)
  }

  @Test
  fun `test a block drawn upwards makes the top line primary`() {
    val session = Session(three, caretOffset = 19)
    session.type("<C-V>kk")
    assertEquals(0, session.editor.primaryCaret().getBufferPosition().line)
  }

  @Test
  fun `test VS Code is told which caret is primary`() {
    val session = Session(three)
    session.type("<C-V>jjll")
    // VS Code takes its primary from `selections[0]`, so the block's moving corner has to be first
    // even though it is the last caret in the document.
    assertEquals(2, session.fake.selections[0].active.line)
  }

  @Test
  fun `test leaving blockwise visual leaves one caret`() {
    val session = Session(three)
    session.type("<C-V>jj<Esc>")
    assertEquals(1, session.editor.carets().size)
    assertEquals(1, session.fake.selections.size)
  }

  // Yank and put, which is the register carrying a rectangle rather than a run of text.

  @Test
  fun `test blockwise yank and put inserts a rectangle`() {
    assertEquals("oone two\ntthree four\nffive six", type(three, "<C-V>jjyP"))
  }
}
