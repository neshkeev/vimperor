/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.parser.errors

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a user is told when a line of their config will not parse.
 *
 * ANTLR's own diagnostic names the rule it was in and every token that would have been legal, which
 * for this grammar is hundreds of names. It belongs in a log. What reaches the user is Vim's
 * wording, and `lastParseErrors` is what startup reports after loading a vimrc.
 *
 * In `src/test`, so it runs on JS as well as the JVM, and deliberately: the first version of this
 * built a `Regex` that Java accepts and JavaScript refuses, and it threw from a companion object
 * initialiser - so every parse died, not only a failing one. A JVM-only test would have passed.
 */
class ParserErrorMessageTest {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
  }

  private fun errorsFor(script: String): List<String> {
    injector.vimscriptParser.parse(script)
    return injector.vimscriptParser.lastParseErrors.toList()
  }

  /**
   * A line that cannot even *start* a command gets Vim's own message for exactly that.
   *
   * `^` rather than a plausible-looking word: a name-shaped line like `notacommand` parses fine and
   * fails later, at command lookup, which reports its own E492. This is about the parse failing.
   */
  @Test
  fun `test a line that cannot start a command is reported as E492`() {
    assertEquals(listOf("E492: Not an editor command: ^"), errorsFor("^\n"))
  }

  /**
   * The whole rest of the line, not just the first word: `foo bar` is not the command `foo` with a
   * bad argument, it is not a command, and that is what Vim prints.
   */
  @Test
  fun `test the argument is part of the reported command`() {
    assertEquals(listOf("E492: Not an editor command: ^abc def"), errorsFor("^abc def\n"))
  }

  /** An error that is not an unknown command keeps its position and loses only the token dump. */
  @Test
  fun `test other errors keep their position and drop the token list`() {
    val errors = errorsFor("echo 1 +\n")
    assertTrue(errors.isNotEmpty(), "expected a parse error")
    assertTrue(errors.none { it.contains("expecting {") }, "the expected-token set should be gone: $errors")
  }

  /** A line that parses says nothing. */
  @Test
  fun `test a valid script reports nothing`() {
    assertEquals(emptyList(), errorsFor("echo 1\n"))
  }
}
