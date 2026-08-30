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
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vim keystrokes, on a VS Code document.
 *
 * The engine already ran headlessly and in a JavaScript runtime; what is new here is the host. A
 * key arrives, `KeyHandler` maps it, builds a command and runs the handler, the handler mutates a
 * buffer VS Code has not seen yet, and the flush turns all of it into one edit on the document.
 *
 * Which is to say: this is the first test where a Vim command changes a VS Code document.
 */
class VsCodeKeystrokeTest {

  private class Session(text: String, caretOffset: Int) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      // The engine owns the builtin command trie but does not fill it; without this the key
      // handler recognises nothing. See `VsCodeInjector.keyGroup`.
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)

      // `KeyHandler` is a singleton and its state outlives a test. Without this, one test that
      // throws mid-command leaves a half-built command behind and the next test's keys vanish into
      // it - which reads as a broken command rather than as a broken neighbour.
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

  @Test
  fun `test x deletes the character under the caret`() {
    assertEquals("bc", type("abc", "x"))
  }

  @Test
  fun `test a count repeats the deletion`() {
    assertEquals("c", type("abc", "2x"))
  }

  @Test
  fun `test dw deletes a word`() {
    assertEquals("two three", type("one two three", "dw"))
  }

  @Test
  fun `test x reaches the document as a one-character edit`() {
    // Not just the right text: the right edit. A Vim command that rewrote the file would pass an
    // assertion on the content and cost the undo stack, decorations and folding on every keystroke.
    val session = Session("the quick brown fox", 4)
    session.type("x")

    assertEquals("the uick brown fox", session.fake.document.content)
    assertEquals(listOf(RecordedEdit(4, 5, "")), session.fake.recordedEdits)
  }

  @Test
  fun `test the caret VS Code ends up with is the one Vim moved`() {
    val session = Session("one two three", 0)
    session.type("dw")

    assertEquals("two three", session.fake.document.content)
    assertEquals(0, session.fake.selection.active.character)
  }

  @Test
  fun `test a whole command reaches the document as a single edit`() {
    // `2x` deletes twice inside the engine. VS Code hears once, which is also what makes one
    // keystroke one undo step without the CommandProcessor wrapper IntelliJ needs.
    val session = Session("abcdef", 0)
    session.type("2x")

    assertEquals("cdef", session.fake.document.content)
    assertEquals(1, session.fake.recordedEdits.size)
  }
}
