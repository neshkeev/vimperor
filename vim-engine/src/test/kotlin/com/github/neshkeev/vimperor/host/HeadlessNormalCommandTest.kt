/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:normal` from Insert or Replace, and over a range.
 *
 * From Insert the keys run once and that mode comes back. The line that used to lose it is a range
 * past the end of the buffer: the keys never run, and the mode still has to be the one we left.
 * From Normal a range still runs on every line that exists.
 */
class HeadlessNormalCommandTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(text: String): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
    return Session(text)
  }

  /**
   * `:999normal` on one line is 0-based line 998. `lineCount()` is 1, and `998 > 1` is the break.
   * Restoring Insert has to happen on that path, not only after the keys.
   */
  @Test
  fun `test a line past the end from Insert restores Insert`() {
    val s = session("abc")
    s.editor.mode = Mode.INSERT

    assertEquals(ExecutionResult.Success, s.run("999normal x"))

    assertEquals(Mode.INSERT, s.editor.mode, "Insert is restored even though the keys never ran")
    assertEquals("abc", s.editor.text)
  }

  @Test
  fun `test a line past the end from Replace restores Replace`() {
    val s = session("abc")
    s.editor.mode = Mode.REPLACE

    assertEquals(ExecutionResult.Success, s.run("999normal x"))

    assertEquals(Mode.REPLACE, s.editor.mode, "Replace is restored even though the keys never ran")
    assertEquals("abc", s.editor.text)
  }

  @Test
  fun `test normal from Insert deletes one character and returns to Insert`() {
    val s = session("abc")
    s.editor.mode = Mode.INSERT

    assertEquals(ExecutionResult.Success, s.run("normal x"))

    assertEquals("bc", s.editor.text)
    assertEquals(Mode.INSERT, s.editor.mode)
  }

  /** From Insert a range is still one run, on the first line of the range. */
  @Test
  fun `test a range from Insert runs on the first line only`() {
    val s = session("abc\ndef\nghi")
    s.editor.mode = Mode.INSERT

    assertEquals(ExecutionResult.Success, s.run("1,3normal x"))

    assertEquals("bc\ndef\nghi", s.editor.text)
    assertEquals(Mode.INSERT, s.editor.mode)
  }

  @Test
  fun `test a range from Normal runs on every line`() {
    val s = session("abc\ndef\nghi")

    assertEquals(ExecutionResult.Success, s.run("1,3normal x"))

    assertEquals("bc\nef\nhi", s.editor.text)
    assertEquals(Mode.NORMAL(), s.editor.mode)
  }

  @Test
  fun `test a line past the end from Normal changes nothing`() {
    val s = session("abc")

    assertEquals(ExecutionResult.Success, s.run("999normal x"))

    assertEquals("abc", s.editor.text)
    assertEquals(Mode.NORMAL(), s.editor.mode)
  }
}
