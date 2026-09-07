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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rest of the `:echo` family, and `:filter` over what they print, on both targets.
 *
 * `:echomsg`, `:echoerr`, `:echon` and `:eval` have no rule of their own in the grammar, so they
 * arrive as a string where `:echo` arrives as a list of parsed expressions. Rather than parse that
 * string a second way, they hand it back to the parser as an `:echo` and take the expressions off
 * it - which is what these tests are really checking: that a string, a concatenation and a variable
 * all reach them the way they reach `:echo`.
 */
class HeadlessEchoAndFilterTest {

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

  // The `:echo` family.

  @Test
  fun `test echomsg prints what echo would`() {
    val s = session()
    s.run("""echomsg "hello"""")

    assertEquals(listOf("hello"), s.output)
  }

  /** The expressions come from the parser, so everything `:echo` can say, these can say. */
  @Test
  fun `test the argument is parsed as expressions rather than as text`() {
    val s = session()
    s.run("""let g:who = "world"""")
    s.run("""echomsg "hello " . g:who""")

    assertEquals(listOf("hello world"), s.output)
  }

  @Test
  fun `test more than one expression is separated by a space`() {
    val s = session()
    s.run("""echomsg "a" "b"""")

    assertEquals(listOf("a b"), s.output)
  }

  /** `:echoerr` is an error rather than a message, and aborts what it was running. */
  @Test
  fun `test echoerr reports rather than prints`() {
    val s = session()
    s.run("""echoerr "went wrong"""")

    assertEquals(emptyList(), s.output)
    assertTrue(s.messages.lastError?.contains("went wrong") == true, "got ${s.messages.lastError}")
  }

  /** ...which is exactly what makes `silent! echoerr` a way of trying something. */
  @Test
  fun `test silent bang swallows an echoerr`() {
    val s = session()
    s.run("""silent! echoerr "went wrong"""")

    assertNull(s.messages.lastError)
  }

  @Test
  fun `test echon prints without the line break after it`() {
    val s = session()
    s.run("""echon "no break"""")

    assertEquals(listOf("no break"), (injector.outputPanel as HeadlessOutputPanelService).lines)
  }

  @Test
  fun `test eval evaluates and says nothing`() {
    val s = session()
    s.run("""let g:x = 1""")
    s.run("""eval 1 + 1""")

    assertEquals(emptyList(), s.output)
    assertNull(s.messages.lastError)
  }

  @Test
  fun `test echohl and undojoin are accepted in silence`() {
    val s = session()
    for (line in listOf("echohl WarningMsg", "echohl None", "undojoin")) {
      s.run(line)
    }

    assertEquals(emptyList(), s.output)
    assertNull(s.messages.lastError)
  }

  // `:filter`

  @Test
  fun `test filter keeps only the lines that match`() {
    val s = session()
    s.run("""filter /keep/ echo "keep me"""")
    assertEquals(listOf("keep me"), s.output)

    s.run("""filter /keep/ echo "drop me"""")
    assertEquals(listOf("keep me"), s.output, "the second line should not have been printed")
  }

  /** The bang inverts it, which is how you print everything *except* something. */
  @Test
  fun `test the bang keeps the lines that do not match`() {
    val s = session()
    s.run("""filter! /drop/ echo "drop me"""")
    assertEquals(emptyList(), s.output)

    s.run("""filter! /drop/ echo "keep me"""")
    assertEquals(listOf("keep me"), s.output)
  }

  /** The unit is the line, so a command that prints a block is filtered inside it. */
  @Test
  fun `test a block of output is filtered line by line`() {
    val s = session()
    s.run("""filter /two/ echo "one\ntwo\nthree"""")

    assertEquals(listOf("two"), s.output)
  }

  /** A pattern with no delimiter runs to the first space, because nothing else could end it. */
  @Test
  fun `test an undelimited pattern is taken up to the first space`() {
    val s = session()
    s.run("""filter keep echo "keep me"""")

    assertEquals(listOf("keep me"), s.output)
  }

  @Test
  fun `test the filter lasts exactly one command`() {
    val s = session()
    s.run("""filter /keep/ echo "keep me"""")
    s.run("""echo "afterwards"""")

    assertEquals(listOf("keep me", "afterwards"), s.output)
    assertNull(injector.messages.outputFilter)
  }

  // `:unsilent`

  @Test
  fun `test unsilent gets a line out of a silent block`() {
    val s = session()
    s.run("""silent unsilent echo "important"""")

    assertEquals(listOf("important"), s.output)
  }

  @Test
  fun `test the accepted modifiers run their command`() {
    val s = session()
    for (modifier in listOf("confirm", "sandbox", "noswapfile", "topleft", "botright", "aboveleft", "belowright", "tab")) {
      s.run("""$modifier echo "$modifier"""")
    }

    assertEquals(
      listOf("confirm", "sandbox", "noswapfile", "topleft", "botright", "aboveleft", "belowright", "tab"),
      s.output,
    )
  }
}
