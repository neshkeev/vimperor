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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `histadd()`, `histget()`, `histdel()` and `histnr()`, and the names Vim lets you call a history.
 *
 * `:h hist-names` gives five histories and **seven** names for them, because the search history
 * answers to both `/` and `?` - the two keys that open it. Only `/` was recognised, so every one of
 * the four functions failed outright when passed `?`: `histadd` returned zero and added nothing,
 * `histget` answered the empty string, `histnr` answered -1.
 *
 * It fails the way a typo fails rather than the way an error fails, which is why a test is worth
 * having for something this small: a `?` in a config is a name Vim accepts, so nothing reports it,
 * and the function quietly does nothing. Ported from IdeaVim.
 *
 * The full-word forms are checked alongside, since `getTypeByString` matches them by prefix - and a
 * prefix match is the kind of thing a one-character change can break without any test noticing.
 */
class HeadlessHistoryFunctionTest {

  private class Session {
    val editor = TestVimEditor("one\ntwo", listOf(TestVimCaret(0, isPrimary = true)))

    fun evaluate(expression: String): String {
      val parsed = injector.vimscriptParser.parseExpression(expression)
        ?: throw AssertionError("failed to parse: $expression")
      return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test a question mark names the search history, as a slash does`() {
    val s = session()

    assertEquals("1", s.evaluate("""histadd('?', 'needle')"""))

    assertEquals("needle", s.evaluate("""histget('?', -1)"""))
    assertEquals("needle", s.evaluate("""histget('/', -1)"""), "both names are the same history")
  }

  @Test
  fun `test histnr answers for a question mark`() {
    val s = session()
    s.evaluate("""histadd('/', 'needle')""")

    assertEquals(s.evaluate("""histnr('/')"""), s.evaluate("""histnr('?')"""))
  }

  @Test
  fun `test histdel reaches the search history through a question mark`() {
    val s = session()
    s.evaluate("""histadd('/', 'needle')""")

    assertEquals("1", s.evaluate("""histdel('?')"""))

    assertEquals("", s.evaluate("""histget('/', -1)"""))
  }

  @Test
  fun `test the histories that were already reachable still are`() {
    val s = session()

    s.evaluate("""histadd(':', 'wq')""")
    s.evaluate("""histadd('=', '1+1')""")
    s.evaluate("""histadd('@', 'answer')""")

    assertEquals("wq", s.evaluate("""histget(':', -1)"""))
    assertEquals("1+1", s.evaluate("""histget('=', -1)"""))
    assertEquals("answer", s.evaluate("""histget('@', -1)"""))
  }

  @Test
  fun `test a history can still be named by its word, which is matched by prefix`() {
    val s = session()

    s.evaluate("""histadd('search', 'needle')""")

    assertEquals("needle", s.evaluate("""histget('?', -1)"""))
    assertEquals("needle", s.evaluate("""histget('sea', -1)"""))
  }
}
