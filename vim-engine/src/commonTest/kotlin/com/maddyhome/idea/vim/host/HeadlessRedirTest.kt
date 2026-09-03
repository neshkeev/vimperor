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
import kotlin.test.assertTrue

/**
 * `:redir` - catching what a command printed.
 *
 * The test that carries the design is [test redirection catches output that silent is hiding]. The
 * idiom `:redir => x | silent map | redir END` is why anybody uses this command, and it works only
 * if the capture happens *before* `:silent` decides not to draw. Put the tap on the other side of
 * that check and every test here still passes except that one, and the command is useless.
 *
 * The rest is the four destinations. Two of them - a register and a variable - are one code path,
 * because both are `LValueExpression`, and they are tested apart anyway: sharing an implementation
 * is a reason to write fewer lines, not fewer tests.
 */
class HeadlessRedirTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one two", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    /** Empties the panel, so what a following command prints can be read on its own. */
    fun clearPanel() = injector.outputPanel.clear(editor, HeadlessExecutionContext)

    /**
     * What is in a register.
     *
     * Read from the register group rather than through `:echo @a`, which would add the panel's own
     * line break to the one the redirection wrote and make every expectation here ambiguous about
     * which newline it was checking.
     */
    fun register(name: Char): String {
      val stored = injector.registerGroup.getRegister(editor, HeadlessExecutionContext, name) ?: return ""
      return injector.parser.toPrintableString(stored.keys)
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // ---- the reason the command exists -----------------------------------------------------------

  /**
   * The whole point, and the one ordering that cannot be got wrong quietly.
   *
   * `:silent` hides output from the screen; `:redir` catches it anyway. A capture placed after the
   * silence check would leave every real use of this command returning an empty string, and every
   * other test in this file would still pass.
   */
  @Test
  fun `test redirection catches output that silent is hiding`() {
    val s = session()
    s.run("redir @a")
    s.clearPanel()
    s.run("silent echo 'caught'")
    s.run("redir END")

    assertEquals(emptyList(), s.panel.lines, "silent means it was not drawn")
    assertEquals("caught\n", s.register('a'), "and redirected all the same")
  }

  // ---- the four destinations -------------------------------------------------------------------

  @Test
  fun `test output goes to a register`() {
    val s = session()
    s.run("redir @a")
    s.run("echo 'first'")
    s.run("echo 'second'")
    s.run("redir END")

    assertEquals("first\nsecond\n", s.register('a'))
  }

  @Test
  fun `test an uppercase register name appends, as everywhere else in Vim`() {
    val s = session()
    s.run("redir @a")
    s.run("echo 'first'")
    s.run("redir END")

    s.run("redir @A")
    s.run("echo 'second'")
    s.run("redir END")

    assertEquals("first\nsecond\n", s.register('a'))
  }

  @Test
  fun `test the arrows after a register name say the same thing`() {
    val s = session()
    s.run("redir @a>")
    s.run("echo 'first'")
    s.run("redir END")

    s.run("redir @a>>")
    s.run("echo 'second'")
    s.run("redir END")

    assertEquals("first\nsecond\n", s.register('a'))
  }

  @Test
  fun `test output goes to a variable`() {
    val s = session()
    s.run("redir => g:caught")
    s.run("echo 'hello'")
    s.run("redir END")

    s.clearPanel()
    s.run("echo g:caught")
    assertEquals("hello\n\n", s.panel.lines.joinToString(""))
  }

  @Test
  fun `test a double arrow appends to a variable that is already there`() {
    val s = session()
    s.run("let g:caught = 'before '")
    s.run("redir =>> g:caught")
    s.run("echo 'after'")
    s.run("redir END")

    s.clearPanel()
    s.run("echo g:caught")
    assertTrue(s.panel.lines.joinToString("").startsWith("before after"), "got: ${s.panel.lines}")
  }

  @Test
  fun `test output goes to a file`() {
    val s = session()
    s.run("redir > /work/out.txt")
    s.run("echo 'to a file'")
    s.run("redir END")

    assertEquals("to a file\n", s.files.written["/work/out.txt"])
  }

  @Test
  fun `test a file that is already there is not clobbered without a bang`() {
    val s = session()
    s.files.written["/work/out.txt"] = "precious"
    s.run("redir > /work/out.txt")

    assertEquals("""E189: "/work/out.txt" exists (add ! to override)""", s.messages.lastError)
    assertEquals("precious", s.files.written["/work/out.txt"])
  }

  @Test
  fun `test the bang overwrites the file it refused to`() {
    val s = session()
    s.files.written["/work/out.txt"] = "precious"
    s.run("redir! > /work/out.txt")
    s.run("echo 'replaced'")
    s.run("redir END")

    assertEquals("replaced\n", s.files.written["/work/out.txt"])
  }

  @Test
  fun `test a double arrow appends to the file`() {
    val s = session()
    s.files.written["/work/out.txt"] = "before\n"
    s.run("redir >> /work/out.txt")
    s.run("echo 'after'")
    s.run("redir END")

    assertEquals("before\nafter\n", s.files.written["/work/out.txt"])
  }

  // ---- how it behaves ---------------------------------------------------------------------------

  /**
   * Vim: "calls to `:redir` will close any active redirection before starting redirection to the
   * new target". So the first target keeps what it caught rather than losing it.
   */
  @Test
  fun `test a second redir closes the first instead of losing it`() {
    val s = session()
    s.run("redir @a")
    s.run("echo 'to a'")
    s.run("redir @b")
    s.run("echo 'to b'")
    s.run("redir END")

    assertEquals("to a\n", s.register('a'))
    assertEquals("to b\n", s.register('b'))
  }

  /**
   * Written through on every message rather than buffered until `:redir END`.
   *
   * Vim buffers a variable until the end; doing that for everything would mean a script that threw
   * halfway - which is exactly when somebody is redirecting output to find out why - loses
   * everything it captured.
   */
  @Test
  fun `test what has been caught is readable before the redirection ends`() {
    val s = session()
    s.run("redir @a")
    s.run("echo 'partway'")

    assertEquals("partway\n", s.register('a'), "no :redir END yet")
  }

  @Test
  fun `test ending a redirection nobody started is E1185`() {
    val s = session()
    s.run("redir END")

    assertEquals("E1185: Missing :redir END", s.messages.lastError)
  }

  @Test
  fun `test redir on its own says where the output is going`() {
    val s = session()
    s.run("redir")
    assertEquals("not redirecting", s.messages.getStatusBarMessage())

    s.run("redir @a")
    s.run("redir")
    assertEquals("@a", s.messages.getStatusBarMessage())
  }

  @Test
  fun `test an argument that is not one of the four forms is E475`() {
    val s = session()
    s.run("redir sideways")

    assertEquals("E475: Invalid argument: sideways", s.messages.lastError)
  }

  @Test
  fun `test a register nobody can write to is E354`() {
    val s = session()
    s.run("redir @%")

    // The name is missing from the text because the bundle wraps `{0}` in single quotes, which
    // MessageFormat reads as a literal. That is pre-existing and shared with IdeaVim; fixing it
    // changes what users see for every invalid register and belongs in its own commit.
    assertEquals("E354: Invalid register name: {0}", s.messages.lastError)
  }

  /**
   * A table, which is what redirection is nearly always pointed at.
   *
   * `:echo` is one message and easy to catch; `:registers` and `:map` print blocks, and a capture
   * that only ever handled one line would pass every test above and fail the actual use.
   */
  @Test
  fun `test a command that prints a table is caught whole`() {
    val s = session()
    s.run("highlight Todo guifg=Red")
    s.run("redir @a")
    s.run("silent highlight")
    s.run("redir END")

    assertTrue("Todo" in s.register('a'), "the table should have been caught: ${s.register('a')}")
  }

  /** A file service that cannot write is `E212`, and it has to reach the user rather than vanish. */
  @Test
  fun `test a write that fails is reported`() {
    val s = session()
    s.run("redir > /work/out.txt")
    s.files.writeFailure = "disk is full"
    s.run("echo 'lost'")

    assertTrue(
      s.messages.lastError?.startsWith("E212") == true,
      "the failure should be reported, got ${s.messages.lastError}",
    )
  }

}
