/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:redraw`, `:sleep`, `:checktime`, and the commands that report that this build does not have them.
 *
 * The second half is the interesting one. Vim answers `E319: Sorry, the command is not available in
 * this version` for a command its build was compiled without, and that is a different message from
 * `E492: Not an editor command` on purpose: `E492` says "no such command, check your spelling" and
 * `E319` says "that command, and not here". Every command answering `E319` in this fork is one
 * whose *subject* is missing - no embedded interpreter, no terminal to suspend to, no GUI window to
 * move, no swap file - rather than one that could be written and has not been. Those still report
 * `E492`, and the last test here is what keeps that line from moving.
 */
class HeadlessScreenCommandTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val redraws: HeadlessRedrawService get() = injector.redrawService as HeadlessRedrawService

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
  fun `test redraw asks the host to redraw`() {
    val s = session()
    s.run("redraw")

    assertEquals(1, s.redraws.redraws)
    assertEquals(0, s.redraws.statusLineRedraws)
  }

  @Test
  fun `test redrawstatus asks for the status line alone`() {
    val s = session()
    s.run("redrawstatus")

    assertEquals(0, s.redraws.redraws)
    assertEquals(1, s.redraws.statusLineRedraws)
  }

  /** Both are registered and return at once, with the reason written at each declaration. */
  @Test
  fun `test sleep and checktime are accepted in silence`() {
    val s = session()
    for (line in listOf("sleep", "sleep 100m", "checktime", "checktime 1")) {
      assertEquals(ExecutionResult.Success, s.run(line), "`:$line` should have been accepted")
    }
    assertNull(s.messages.lastError)
  }

  /** Vim's own answer for a build without the feature, in place of "no such command". */
  @Test
  fun `test the commands this build does not have report E319`() {
    val s = session()
    val unavailable = listOf(
      "python print(1)", "py3 print(1)", "perl 1", "ruby 1", "lua 1", "tcl 1",
      "gui", "gvim", "hardcopy", "tearoff Foo", "tmenu Foo bar", "tunmenu Foo",
      "winpos 0 0", "winsize 80 24", "options", "intro", "exusage", "viusage",
      "stop", "suspend", "open",
      "recover", "rviminfo", "wviminfo", "rundo x", "wundo x", "undolist", "mksession", "breakadd here",
      "pclose", "pedit x", "psearch x", "isearch x", "ijump x", "ilist x", "isplit x",
      "ownsyntax java", "compiler gcc", "helpgrep x", "cquit", "trust",
    )

    for (line in unavailable) {
      s.messages.clearError()
      s.run(line)
      assertTrue(s.messages.lastError?.contains("E319") == true, "`:$line` should be E319, got ${s.messages.lastError}")
    }
  }

  /**
   * A command that is merely not written yet still reports `E492`, which is what makes `E319` mean
   * something.
   *
   * `:profile` and `:debug` are the two nearest the line now that `:mkvimrc` and the tag commands
   * are written. This engine evaluates Vimscript, so it could time a function and it could stop
   * before each line of one; claiming the build does not have those would be a claim about the work
   * rather than about the feature.
   */
  @Test
  fun `test a command that could be written still reports E492`() {
    val s = session()
    for (line in listOf("profile start x", "debug echo 1")) {
      s.messages.clearError()
      s.run(line)
      assertTrue(
        s.messages.lastError?.contains("E492") == true || s.messages.lastError?.contains("Not an editor") == true,
        "`:$line` should still be E492, got ${s.messages.lastError}",
      )
    }
  }
}
