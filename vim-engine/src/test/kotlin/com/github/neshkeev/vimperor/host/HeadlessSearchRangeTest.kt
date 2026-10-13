/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A range written as a search: `:/pattern/`, and the empty form `:/` that stands for the last one.
 *
 * `:/` on its own threw `StringIndexOutOfBoundsException`. The address is parsed by stripping the
 * delimiters off the pattern, and the one-character string `/` has no inside to strip: the closing
 * delimiter and the opening one are the same character, so `substring(1, 0)` was asked for. Vim
 * reads an empty pattern there as the last search pattern, which is also what `//` means in `:s//`
 * - and reports `E35: No previous regular expression` when there has not been one.
 *
 * An exception out of a *range* is worse than it looks, because the range is parsed before the
 * command is known. `:/` and `:/,+2d` fail the same way, so a stray slash on the command line took
 * out the whole line rather than reporting what was wrong with it. Ported from IdeaVim.
 */
class HeadlessSearchRangeTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
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

  private fun session(text: String = "one\ntwo\nthree\ntwo\nfive"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  @Test
  fun `test a bare slash with no previous pattern reports E35 rather than throwing out of the parse`() {
    val s = session()
    injector.searchGroup.lastSearchPattern = null

    val thrown = assertFailsWith<ExException> { s.run("/") }

    assertTrue(
      thrown.message?.contains("E35") == true,
      "a range of `/` with no previous pattern should be E35, not an index error. Said: ${thrown.message}",
    )
  }

  @Test
  fun `test a bare question mark with no previous pattern reports E35 too`() {
    val s = session()
    injector.searchGroup.lastSearchPattern = null

    val thrown = assertFailsWith<ExException> { s.run("?") }

    assertTrue(thrown.message?.contains("E35") == true, "said: ${thrown.message}")
  }

  @Test
  fun `test a bare slash moves to the next match of the last pattern`() {
    val s = session()
    injector.searchGroup.lastSearchPattern = "three"

    s.run("/")

    assertEquals(
      2,
      s.editor.offsetToBufferPosition(s.caret.offset).line,
      "a range with no command is Vim's goto-line, so `:/` should land on the match",
    )
  }

  @Test
  fun `test an empty pattern between delimiters uses the last pattern as its range`() {
    val s = session()
    injector.searchGroup.lastSearchPattern = "two"

    s.run("//d")

    assertEquals(
      "one\nthree\ntwo\nfive",
      s.editor.text().toString(),
      "`://d` should have deleted the next line matching the last pattern",
    )
  }

  @Test
  fun `test a pattern that is given is still the one used`() {
    val s = session()
    injector.searchGroup.lastSearchPattern = "two"

    s.run("/five/d")

    assertEquals("one\ntwo\nthree\ntwo", s.editor.text().toString())
  }
}
