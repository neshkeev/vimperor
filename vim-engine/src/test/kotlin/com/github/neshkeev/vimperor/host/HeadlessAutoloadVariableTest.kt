/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A variable name may contain `#`, the way a function name already could.
 *
 * `g:textobj#quote#doubleMotion` is how an autoload plugin is configured. The grammar used to
 * accept `#` only in a function name, so the assignment did not parse, and a config runs with
 * errors suppressed.
 */
class HeadlessAutoloadVariableTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val output: List<String>
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.map { it.trimEnd('\n') }

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

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test a namespaced global is stored and read by its full name`() {
    val s = session()

    assertEquals(ExecutionResult.Success, s.run("let g:foo#bar#baz = 'HELLO'"))
    s.run("echo g:foo#bar#baz")

    assertEquals(VimString("HELLO"), injector.variableService.getGlobalVariableValue("foo#bar#baz"))
    assertEquals(listOf("HELLO"), s.output)
  }

  @Test
  fun `test a namespaced name without a scope is the same variable`() {
    val s = session()

    s.run("let foo#bar = 'HELLO'")
    s.run("echo foo#bar")

    assertEquals(VimString("HELLO"), injector.variableService.getGlobalVariableValue("foo#bar"))
    assertEquals(listOf("HELLO"), s.output)
  }

  @Test
  fun `test the last segment is not a variable of its own`() {
    val s = session()
    s.run("let g:foo#bar#baz = 'HELLO'")

    s.run("echo g:baz")

    assertNull(injector.variableService.getGlobalVariableValue("baz"))
    assertTrue(s.messages.lastError?.contains("E121: Undefined variable: g:baz") == true, "got ${s.messages.lastError}")
  }

  /**
   * `==#` is a comparison, and the `#` there is not a namespace separator.
   *
   * The prefix is `name#` repeated. `g:foo==#'abc'` has to stay a comparison of `g:foo`, or every
   * case-sensitive compare written after a variable would have been swallowed by the new rule.
   */
  @Test
  fun `test a case sensitive comparison still follows a variable name`() {
    val s = session()
    s.run("let g:foo = 'abc'")

    s.run("echo g:foo==#'abc'")

    assertEquals(listOf("1"), s.output)
  }
}
