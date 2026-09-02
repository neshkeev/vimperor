/*
 * Copyright 2026 Nikita Eshkeev
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
 * `J`, `gJ` and `.` - three commands that live in the IntelliJ module rather than in vim-engine.
 *
 * The JS command registry is generated from the engine's own annotation output, so it carries the
 * 250 actions the engine declares and none of the 14 the IntelliJ plugin declares. Most of that 14
 * is IntelliJ furniture - inlays, the plugin toggle, `K` for quick documentation - but four entries
 * are ordinary Vim: `J`, `gJ`, `.` and `g@`. Nothing in this host had asked for them, so nothing
 * had noticed they were gone.
 *
 * These tests say what the commands should do. Whether they currently do it is the question.
 */
class VsCodeJoinAndRepeatTest {

  private class Session(text: String, caretOffset: Int) {
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

  // J - join, inserting a single space at the seam and dropping the second line's indent.

  @Test
  fun `test J joins two lines with a space`() {
    assertEquals("one two", type("one\ntwo", "J"))
  }

  @Test
  fun `test J strips the leading whitespace of the joined line`() {
    assertEquals("one two", type("one\n    two", "J"))
  }

  @Test
  fun `test J with a count joins that many lines`() {
    assertEquals("one two three", type("one\ntwo\nthree", "3J"))
  }

  @Test
  fun `test J leaves the caret at the seam`() {
    val session = Session("one\ntwo", 0)
    session.type("J")
    assertEquals(3, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test J after the lines were typed rather than seeded`() {
    assertEquals("one two", type("", "ione<CR>    two<Esc>ggJ"))
  }

  // gJ - join without touching the whitespace on either side.

  @Test
  fun `test gJ joins without inserting a space`() {
    assertEquals("onetwo", type("one\ntwo", "gJ"))
  }

  @Test
  fun `test gJ keeps the leading whitespace of the joined line`() {
    assertEquals("one    two", type("one\n    two", "gJ"))
  }

  // . - repeat the last change.

  @Test
  fun `test dot repeats a delete`() {
    assertEquals("c", type("abc", "x."))
  }

  @Test
  fun `test dot repeats an insert`() {
    assertEquals("XXabc", type("abc", "iX<Esc>."))
  }

  @Test
  fun `test dot repeats an operator with its count`() {
    assertEquals("three", type("one two three", "dw."))
  }

  @Test
  fun `test dot takes a new count when given one`() {
    assertEquals("d", type("abcd", "x2."))
  }
}
