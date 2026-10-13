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
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:continue` and `:break` inside `:for`, over each kind of thing Vim can iterate.
 *
 * `:continue` over a *list* did not terminate. The loop is written as a `while` over an index, and
 * the Continue branch reached for Kotlin's own `continue` - which jumps to the `while` condition and
 * so skips the `index += 1` below it. `for x in [1, 2] | continue | endfor` ran for ever, which in a
 * `.vimperorrc` is a hung editor rather than an error. Falling through to the increment is the fix.
 *
 * The range form - `for x in range(3)` - took a different branch and was never affected, which is
 * why this went unnoticed: the obvious test passes. Ported from IdeaVim.
 *
 * A test here cannot fail politely. Without the fix it hangs, so what the suite reports is a
 * timeout rather than an assertion - which is the honest shape of the bug and the reason the body
 * counts its passes rather than merely checking the result.
 */
class HeadlessLoopTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }

    fun run(script: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        script,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    fun counter(): Int = injector.variableService.getGlobalVariableValue("n")?.toVimNumber()?.value ?: -1
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test continue over a list runs the rest of the iterations and stops`() {
    val s = session()

    s.run(
      """
      let g:n = 0
      for x in [1, 2, 3]
        let g:n = g:n + 1
        continue
        let g:n = g:n + 100
      endfor
      """.trimIndent(),
    )

    assertEquals(3, s.counter(), "every element should have been visited exactly once")
  }

  @Test
  fun `test break over a list stops at the first iteration`() {
    val s = session()

    s.run(
      """
      let g:n = 0
      for x in [1, 2, 3]
        let g:n = g:n + 1
        break
      endfor
      """.trimIndent(),
    )

    assertEquals(1, s.counter())
  }

  @Test
  fun `test continue over a list of lists runs the rest of the iterations and stops`() {
    val s = session()

    s.run(
      """
      let g:n = 0
      for [a, b] in [[1, 2], [3, 4]]
        let g:n = g:n + a
        continue
        let g:n = g:n + 100
      endfor
      """.trimIndent(),
    )

    assertEquals(4, s.counter(), "both pairs should have been unpacked exactly once")
  }

  @Test
  fun `test continue over a range was never broken, which is why this went unnoticed`() {
    val s = session()

    s.run(
      """
      let g:n = 0
      for x in range(3)
        let g:n = g:n + 1
        continue
      endfor
      """.trimIndent(),
    )

    assertEquals(3, s.counter())
  }
}
