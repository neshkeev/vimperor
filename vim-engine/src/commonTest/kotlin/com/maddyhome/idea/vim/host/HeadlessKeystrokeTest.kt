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
 * Keystrokes, on both targets.
 *
 * This is the last vertical the engine has: a key arrives, `KeyHandler` maps it, builds a command
 * from it, and runs the handler. Everything before this drove the engine through an API a host
 * calls deliberately; this drives it the way a user does.
 */
class HeadlessKeystrokeTest {

  private class Buffer(text: String, caretOffset: Int) {
    val caret = TestVimCaret(caretOffset, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
  }

  private fun type(text: String, keys: String, caretOffset: Int = 0): String {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    // The engine owns the builtin command trie but not the filling of it - see
    // `HeadlessInjector.keyGroup`. Without this the key handler recognises nothing.
    engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
    val buffer = Buffer(text, caretOffset)
    val handler = KeyHandler.getInstance()
    val state = handler.keyHandlerState
    for (stroke in injector.parser.parseKeys(keys)) {
      handler.handleKey(buffer.editor, stroke, HeadlessExecutionContext, state)
    }
    return buffer.editor.text
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
}
