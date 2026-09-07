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
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:unlet`, on both targets.
 *
 * The other half of `:let`, which the engine has had all along. What it is for is a config undoing
 * itself - dropping a guard variable so a file can be sourced twice - and the bang is the half that
 * makes that work, because the second sourcing is exactly the one where the variable is already
 * gone.
 */
class HeadlessUnletTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages

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
  fun `test unlet removes a global variable`() {
    val s = session()
    s.run("let g:x = 1")
    assertNotNull(injector.variableService.getGlobalVariableValue("x"))

    s.run("unlet g:x")

    assertNull(injector.variableService.getGlobalVariableValue("x"))
  }

  @Test
  fun `test unlet takes more than one name`() {
    val s = session()
    s.run("let g:a = 1")
    s.run("let g:b = 2")

    s.run("unlet g:a g:b")

    assertEquals(emptyMap(), injector.variableService.getGlobalVariables())
  }

  @Test
  fun `test a name that was never set is an error`() {
    val s = session()
    s.run("unlet g:neverset")

    assertTrue(s.messages.lastError?.contains("E108") == true, "got ${s.messages.lastError}")
  }

  /** The bang, which is the form a config that may be sourced twice actually writes. */
  @Test
  fun `test the bang removes what is there and says nothing about the rest`() {
    val s = session()
    s.run("let g:a = 1")

    assertEquals(ExecutionResult.Success, s.run("unlet! g:a g:neverset"))

    assertNull(injector.variableService.getGlobalVariableValue("a"))
    assertNull(s.messages.lastError)
  }

  /** Vim stops at the first missing name rather than working down the list. */
  @Test
  fun `test without the bang a missing name stops the command`() {
    val s = session()
    s.run("let g:b = 1")

    s.run("unlet g:neverset g:b")

    assertNotNull(injector.variableService.getGlobalVariableValue("b"), "the command should not have got this far")
  }

  /** `v:` belongs to the interpreter, so there is nothing there that deleting could mean. */
  @Test
  fun `test a v variable cannot be deleted`() {
    val s = session()
    s.run("unlet v:count")

    assertTrue(s.messages.lastError?.contains("E795") == true, "got ${s.messages.lastError}")
  }
}
