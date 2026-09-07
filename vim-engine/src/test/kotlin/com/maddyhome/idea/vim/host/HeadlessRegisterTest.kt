/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Yank, put and named registers, on both targets.
 *
 * Registers are where Vim keeps what it cut and copied, and they are not a side note: `dd` filling
 * the unnamed register is why `p` works afterwards, and `"ayy` filling `a` is why `"ap` differs. The
 * clipboard sits behind them too - `"*` and `"+` are the selection and the system clipboard - which
 * is why the register group needed a clipboard manager before any of this could run.
 */
class HeadlessRegisterTest {

  private class Session(text: String, caretOffset: Int) {
    val caret = TestVimCaret(caretOffset, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
  }

  private fun session(text: String, caretOffset: Int = 0): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
    return Session(text, caretOffset)
  }

  private fun Session.type(keys: String) {
    val handler = KeyHandler.getInstance()
    val state = handler.keyHandlerState
    for (stroke in injector.parser.parseKeys(keys)) {
      handler.handleKey(editor, stroke, HeadlessExecutionContext, state)
    }
  }

  @Test
  fun `test deleting a character fills the unnamed register`() {
    val s = session("abc")
    s.type("x")
    assertEquals("a", injector.registerGroup.getRegister(s.editor, HeadlessExecutionContext, '"')?.text)
  }

  @Test
  fun `test deleting into a named register leaves the unnamed one alone`() {
    val s = session("abc")
    s.type("\"ax")
    assertEquals("a", injector.registerGroup.getRegister(s.editor, HeadlessExecutionContext, 'a')?.text)
  }

  @Test
  fun `test yanking a word does not change the buffer`() {
    val s = session("one two")
    s.type("yw")
    assertEquals("one two", s.editor.text)
    assertEquals("one ", injector.registerGroup.getRegister(s.editor, HeadlessExecutionContext, '"')?.text)
  }

  @Test
  fun `test the last delete is the one that lands in the unnamed register`() {
    val s = session("abc")
    s.type("xx")
    assertEquals("b", injector.registerGroup.getRegister(s.editor, HeadlessExecutionContext, '"')?.text)
  }
}
