/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `|` between two ex commands: `:set nu | set rnu`, which Vim has always had and this fork had not.
 *
 * Whether a `|` ends a command or belongs to its argument is a fact about the *command*, not about
 * the line - `:normal 0|w` moves to the first column and then a word, while `:g/x/d | echo 1` runs
 * `d | echo 1` for every matching line because `:g` takes the rest of the line. Vim keeps that in
 * its command table as the `TRLBAR` flag, and here it is two values on `@ExCommand`, carried through
 * the generated table: `barSeparates = false` for a command that swallows the bar, and
 * `delimitedSections` for one whose argument wraps text in a delimiter the user chose - the pattern
 * and replacement of `:s/pat/sub/`, the single pattern of `:sort /pat/`.
 *
 * Ported from IdeaVim (VIM-748).
 *
 * The third group is the one worth reading. A `|` is part of the argument when the character before
 * it is a `\` or a CTRL-V, and the escape is not itself escapable: in `\\|` it is the *second*
 * backslash that escapes the bar, which is what makes `:set langmap=\\|a` work.
 */
class HeadlessMultipleCommandsTest {

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

    val text: String get() = editor.text().toString()
  }

  private fun session(text: String = "one\ntwo\nthree\nfour"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // ---- the ordinary case -------------------------------------------------------------------------

  @Test
  fun `test two commands separated by a bar both run`() {
    val s = session()

    s.run("set number | set ignorecase")

    assertTrue(injector.options(s.editor).number, "the first command should have run")
    assertTrue(injector.options(s.editor).ignorecase, "the second command should have run too")
  }

  @Test
  fun `test a chain of three runs in order`() {
    val s = session()

    s.run("let g:a = 1 | let g:a = g:a + 2 | let g:a = g:a * 3")

    assertEquals(9, injector.variableService.getGlobalVariableValue("a")?.toVimNumber()?.value)
  }

  @Test
  fun `test a range on the first command does not reach the second`() {
    val s = session()

    s.run("2d | 1d")

    assertEquals("three\nfour", s.text, "each command keeps its own range")
  }

  /**
   * Vim abandons the rest of the line when a command fails, which is what makes a chain safe.
   *
   * Asserted on `:s`, which is one of the commands that reaches the next one through
   * [com.maddyhome.idea.vim.vimscript.model.commands.Command.nextCommand]. A chain of `:set`
   * commands is split by the *grammar* instead - `SET` sits in the alternative that ends at a bar -
   * so each is an independent statement of the script and the executor runs both regardless. That
   * is a wider change than this one and is left alone.
   */
  @Test
  fun `test a failure abandons the rest of the line`() {
    val s = session()

    // `&` repeats the last substitute, and there has not been one, so it is `E33` - a real
    // `ExecutionResult.Error` rather than a message. `:s/nosuchtext/` is *not* one here: it reports
    // E486 and still answers Success, so the chain after it runs. That is a separate question from
    // this one and is left alone.
    s.run("& | let g:ran = 1")

    assertEquals(
      null,
      injector.variableService.getGlobalVariableValue("ran"),
      "the second command should not have run after the first failed",
    )
  }

  // ---- commands that take the bar themselves -----------------------------------------------------

  /**
   * `:normal` presses the whole rest of the line, bar included - `|` is a motion of its own.
   *
   * `0|$` is three motions and nothing else, chosen so that the keys cannot leave the key handler
   * part-way through a command: an earlier version of this test pressed `= 1`, and `=` is an
   * operator, so it left one pending and the *next* test in the suite lost its `d`.
   *
   * The two readings land in different places, which is what makes this an assertion rather than an
   * observation: pressed as keys, the caret ends at the end of the first line; split at the bar,
   * `:normal 0` would press one key and `:$` would jump to the last line.
   */
  @Test
  fun `test normal takes the bar as a key to press`() {
    val s = session()

    s.run("normal 0|$")

    assertEquals(
      0,
      s.editor.offsetToBufferPosition(s.caret.offset).line,
      "the bar was a motion, so nothing jumped to the last line",
    )
  }

  /**
   * `:g` takes the whole rest of the line and runs it for each matching line, bars included.
   *
   * Counted rather than merely observed, because both readings of the line leave the variable set
   * and only the *number of times* tells them apart. Three of the four lines hold an `o`, so the
   * bar belonging to `:g` makes it `3 * 11`, and the bar separating at the top level would make it
   * `3 + 10`.
   */
  @Test
  fun `test global takes the bar as part of the command it runs`() {
    val s = session()
    s.run("let g:n = 0")

    s.run("g/o/let g:n = g:n + 1 | let g:n = g:n + 10")

    assertEquals(33, injector.variableService.getGlobalVariableValue("n")?.toVimNumber()?.value)
  }

  // ---- commands whose argument wraps text in a delimiter -----------------------------------------

  @Test
  fun `test a bar inside a substitute pattern is part of the pattern`() {
    val s = session("a|b\nc")

    s.run("1s/a|b/X/")

    assertEquals("X\nc", s.text, "the bar is inside the pattern, so it is not a separator")
  }

  @Test
  fun `test a bar after a substitute separates the next command`() {
    val s = session("one\ntwo\nthree\nfour")

    s.run("1s/one/ONE/ | 2s/two/TWO/")

    assertEquals("ONE\nTWO\nthree\nfour", s.text)
  }

  @Test
  fun `test a bar after a sort separates the next command`() {
    val s = session("b\na\nlast")

    s.run("1,2sort | 3d")

    assertEquals("a\nb", s.text)
  }

  // ---- the escapes -------------------------------------------------------------------------------

  @Test
  fun `test a backslash before the bar keeps it in the argument`() {
    val s = session()

    s.run("""let g:a = 'x\|y'""")

    assertEquals(
      """x\|y""",
      injector.variableService.getGlobalVariableValue("a")?.toVimString()?.value,
      "the bar is escaped, so the line is one command and the backslash stays in the value",
    )
  }

  /**
   * `\|` inside a pattern is Vim's *alternation*, not an escaped bar - so it belongs to the pattern
   * for a reason that has nothing to do with escaping, and the plain bar after the closing delimiter
   * still separates. `a\|b` matches `a` or `b`, which is why line one comes out as `X|b` rather than
   * as `X`: the literal bar in the text was never matched.
   */
  @Test
  fun `test alternation in a substitute pattern does not end the command`() {
    val s = session("a|b\nkeep")

    s.run("""1s/a\|b/X/ | 2d""")

    assertEquals("X|b", s.text, "the substitute matched `a`, and `2d` then deleted the second line")
  }

  @Test
  fun `test a command with no bar in it is unaffected`() {
    val s = session()

    s.run("2d")

    assertEquals("one\nthree\nfour", s.text)
  }
}
