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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:function`, `:function {name}` and `:function /{pattern}` - what a script has defined.
 *
 * The command Vim gives you to find out whether your config actually ran. A `.vimperorrc` executes
 * with errors suppressed, so a `function!` that failed to parse leaves no trace at all, and until
 * now there was no way to ask: `:function` was `E492: Not an editor command`.
 *
 * Ported from IdeaVim, which wrote it in five commits - the listing, lambdas, the names anonymous
 * functions get, an unterminated definition, and the description.
 *
 * Two things this fork had to supply that upstream did not, and both are the same reason. Upstream's
 * engine is a JVM module; this one compiles for JS as well, so `java.lang.ref.WeakReference` - which
 * is how a lambda is held, so that it can be reported until the runtime collects it - became the
 * `VimWeakReference` expect/actual pair over `WeakRef` on JS. And `StringBuilder.delete` is JVM-only.
 */
class HeadlessFunctionCommandTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages

    fun run(script: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        script,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    /** What `:function` printed, which is output a user asked for rather than a message. */
    val output: String get() = panel.lines.joinToString("\n")

    /** The panel accumulates, so a test that asks twice has to say which answer it means. */
    fun clearOutput() {
      injector.outputPanel.getCurrentOutputPanel()?.clearText()
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  private fun Session.define(name: String, body: String = "  return 1") {
    run(
      """
      function! $name()
      $body
      endfunction
      """.trimIndent(),
    )
  }

  @Test
  fun `test function lists what a script has defined`() {
    val s = session()
    s.define("Alpha")
    s.define("Beta")

    s.run("function")

    assertTrue(s.output.contains("Alpha()"), "Alpha should be listed. Got:\n${s.output}")
    assertTrue(s.output.contains("Beta()"), "Beta should be listed. Got:\n${s.output}")
  }

  /**
   * The signature, not the body - which is less than Vim prints and is what this port does.
   * `:function {name}` in Vim lists the body and an `endfunction`; here it answers the one line.
   */
  @Test
  fun `test function with a name answers for that one function only`() {
    val s = session()
    s.define("Alpha")
    s.define("Beta")

    s.run("function Alpha")

    assertEquals("function Alpha()", s.output.trim(), "one line, for the one asked for")
  }

  @Test
  fun `test function with a pattern lists the ones that match`() {
    val s = session()
    s.define("AlphaOne")
    s.define("AlphaTwo")
    s.define("Beta")

    s.run("function /Alpha")

    assertTrue(s.output.contains("AlphaOne()"), "Got:\n${s.output}")
    assertTrue(s.output.contains("AlphaTwo()"), "Got:\n${s.output}")
    assertTrue(!s.output.contains("Beta"), "Beta does not match. Got:\n${s.output}")
  }

  @Test
  fun `test function reports the arguments a definition takes`() {
    val s = session()
    s.run(
      """
      function! Takes(a, b)
        return a:a
      endfunction
      """.trimIndent(),
    )

    s.run("function Takes")

    assertTrue(s.output.contains("Takes(a, b)"), "Got:\n${s.output}")
  }

  @Test
  fun `test an unknown function is E123 rather than a listing`() {
    val s = session()
    s.define("Alpha")

    s.run("function NoSuchFunction")

    assertTrue(
      s.messages.lastError?.contains("E123") == true,
      "an undefined function should be E123. Said: ${s.messages.lastError}",
    )
  }

  @Test
  fun `test a definition that never ends is E126`() {
    val s = session()

    s.run(
      """
      function! Unfinished()
        return 1
      """.trimIndent(),
    )

    assertTrue(
      s.messages.lastError?.contains("E126") == true,
      "a missing :endfunction should be E126. Said: ${s.messages.lastError}",
    )
    assertEquals(
      null,
      injector.functionService.getFunctionHandlerOrNull(null, "Unfinished", CommandLineVimLContext),
      "and the function must not be defined",
    )
  }

  /**
   * A lambda is a function and is findable, but only by pattern.
   *
   * `:function` with no argument filters the `<lambda>N` names out, as Vim does - they would bury
   * the list - and `:function /pattern` does not, which is the only way to ask. Both halves are
   * asserted, because the filter is the easier of the two to lose.
   */
  @Test
  fun `test a lambda is found by pattern and left out of the plain listing`() {
    val s = session()
    s.define("Alpha")

    s.run("let g:f = {x -> x + 1}")

    s.clearOutput()
    s.run("function /lambda")
    assertTrue(
      s.output.contains("<lambda>"),
      "a lambda should be reachable by pattern. Got:\n${s.output}",
    )

    s.clearOutput()
    s.run("function")
    assertTrue(
      !s.output.contains("<lambda>") && s.output.contains("Alpha()"),
      "and left out of the plain listing. Got:\n${s.output}",
    )
  }

  @Test
  fun `test trailing rubbish after the name is E488`() {
    val s = session()
    s.define("Alpha")

    s.run("function Alpha zzz")

    assertTrue(
      s.messages.lastError?.contains("E488") == true,
      "said: ${s.messages.lastError}",
    )
  }
}
