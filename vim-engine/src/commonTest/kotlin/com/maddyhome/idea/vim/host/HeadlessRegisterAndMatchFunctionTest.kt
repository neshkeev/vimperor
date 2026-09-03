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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `getreg()`/`setreg()`, and the `match*()` functions that add a highlight.
 *
 * Two families with one thing in common: both exist so that a plugin can get out of the way. A
 * mapping borrows a register and puts it back; a plugin saves the standing highlights, does
 * something noisy and restores them. In both cases the *round trip* is the feature, so it is what
 * the tests are built around.
 *
 * The match family is the more interesting half, because `matchadd()` and `:match` are one table
 * here as they are in Vim - ids 1, 2 and 3 belong to the three commands and `matchadd()` starts at
 * 4. Two tables would have been easier to write and would have made `getmatches()` and
 * `clearmatches()` lie about what is on screen.
 */
class HeadlessRegisterAndMatchFunctionTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one two one three", listOf(caret)).also { caret.editorRef = it }
    val painted: HeadlessMatchHighlighter get() = injector.matchHighlighter as HeadlessMatchHighlighter

    fun value(expression: String): String {
      val parsed = injector.vimscriptParser.parseExpression(expression)
        ?: throw AssertionError("failed to parse: $expression")
      return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
    }

    fun run(line: String) {
      injector.vimscriptExecutor.execute(
        line, editor, HeadlessExecutionContext, skipHistory = true, indicateErrors = true, CommandLineVimLContext,
      )
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // ---- registers -------------------------------------------------------------------------------

  @Test
  fun `test setreg and getreg are a round trip`() {
    val s = session()
    s.value("""setreg('a', 'hello')""")

    assertEquals("hello", s.value("getreg('a')"))
  }

  /**
   * The reason a mapping can borrow a register without ruining somebody's day.
   *
   * Save, use, restore - and the type has to survive with the text, or a line-wise register comes
   * back characterwise and the next put mangles the buffer.
   */
  @Test
  fun `test a register survives being saved and put back with its type`() {
    val s = session()
    s.value("""setreg('a', "one\ntwo\n", 'V')""")
    s.run("""let g:text = getreg('a')""")
    s.run("""let g:type = getregtype('a')""")
    s.value("""setreg('a', 'clobbered')""")
    s.run("""call setreg('a', g:text, g:type)""")

    assertEquals("one\ntwo\n", s.value("getreg('a')"))
    assertEquals("V", s.value("getregtype('a')"))
  }

  @Test
  fun `test a list value is line-wise without having to say so`() {
    val s = session()
    s.value("""setreg('a', ['one', 'two'])""")

    assertEquals("V", s.value("getregtype('a')"))
    assertEquals("one\ntwo\n", s.value("getreg('a')"))
  }

  @Test
  fun `test getreg can answer with a list of lines`() {
    val s = session()
    s.value("""setreg('a', ['one', 'two'])""")

    assertEquals("['one', 'two']", s.value("getreg('a', 1)"))
  }

  @Test
  fun `test a register nobody wrote to is empty rather than an error`() {
    val s = session()

    assertEquals("", s.value("getreg('z')"))
    assertEquals("", s.value("getregtype('z')"))
  }

  // ---- matchadd --------------------------------------------------------------------------------

  /** `matchadd()` starts at 4, because 1, 2 and 3 belong to `:match` and its two twins. */
  @Test
  fun `test matchadd hands back an id above the reserved three`() {
    val s = session()

    assertEquals("4", s.value("""matchadd('Search', 'one')"""))
    assertEquals("5", s.value("""matchadd('Todo', 'two')"""))
  }

  @Test
  fun `test a match added by function is painted like one added by command`() {
    val s = session()
    s.value("""matchadd('Search', 'one')""")

    val painted = s.painted.shown[4]
    assertEquals("Search", painted?.first?.name)
    assertEquals(listOf("one", "one"), painted?.second?.map { s.editor.text.substring(it.startOffset, it.endOffset) })
  }

  /** One table, which is what makes these two functions tell the truth. */
  @Test
  fun `test getmatches lists a colon-match alongside an added one`() {
    val s = session()
    s.run("match ErrorMsg /one/")
    s.value("""matchadd('Search', 'two')""")

    val listed = s.value("getmatches()")
    assertTrue("'ErrorMsg'" in listed, listed)
    assertTrue("'Search'" in listed, listed)
    assertTrue("'id': 1" in listed, "the :match should be there as id 1: $listed")
  }

  @Test
  fun `test an explicit id is honoured and a taken one is refused`() {
    val s = session()

    assertEquals("42", s.value("""matchadd('Search', 'one', 10, 42)"""))
    assertEquals("-1", s.value("""matchadd('Todo', 'two', 10, 42)"""), "42 is taken")
    assertEquals("-1", s.value("""matchadd('Todo', 'two', 10, 2)"""), "2 belongs to :2match")
  }

  @Test
  fun `test matchdelete removes one and reports an id nobody has`() {
    val s = session()
    s.value("""matchadd('Search', 'one')""")

    assertEquals("0", s.value("matchdelete(4)"))
    assertEquals("-1", s.value("matchdelete(4)"))
    assertEquals("[]", s.value("getmatches()"))
  }

  @Test
  fun `test clearmatches takes off everything including a colon-match`() {
    val s = session()
    s.run("match ErrorMsg /one/")
    s.value("""matchadd('Search', 'two')""")
    s.value("clearmatches()")

    assertEquals("[]", s.value("getmatches()"))
  }

  /**
   * Save, be noisy, restore - which is what the pair is for, and why it replaces rather than adds.
   *
   * A plugin that saved three matches and restored them should end with three.
   */
  @Test
  fun `test getmatches and setmatches are a round trip`() {
    val s = session()
    s.value("""matchadd('Search', 'one')""")
    s.value("""matchadd('Todo', 'two')""")
    s.run("""let g:saved = getmatches()""")
    s.value("clearmatches()")
    assertEquals("[]", s.value("getmatches()"))

    s.run("""call setmatches(g:saved)""")
    val restored = s.value("getmatches()")
    assertTrue("'Search'" in restored && "'Todo'" in restored, restored)
    assertEquals("2", s.value("len(getmatches())"), "restoring two should not give four")
  }

  /**
   * `matchaddpos()` is the fast one, and the speed is real: a match made from positions is already
   * ranges, so the repaint after every keystroke has nothing to search for.
   */
  @Test
  fun `test matchaddpos lights the positions it was given`() {
    val s = session()
    s.value("""matchaddpos('Search', [[1, 5, 3]])""")

    val painted = s.painted.shown[4]
    assertEquals(listOf("two"), painted?.second?.map { s.editor.text.substring(it.startOffset, it.endOffset) })
  }

  @Test
  fun `test a bare line number in matchaddpos lights the whole line`() {
    val s = session()
    s.value("""matchaddpos('Search', [1])""")

    val painted = s.painted.shown[4]
    assertEquals(listOf("one two one three"), painted?.second?.map { s.editor.text.substring(it.startOffset, it.endOffset) })
  }

  // ---- matcharg --------------------------------------------------------------------------------

  @Test
  fun `test matcharg answers about the three commands`() {
    val s = session()
    s.run("match ErrorMsg /one/")

    assertEquals("['ErrorMsg', 'one']", s.value("matcharg(1)"))
    assertEquals("['', '']", s.value("matcharg(2)"), "nothing on that channel")
    assertEquals("[]", s.value("matcharg(9)"), "not a channel at all")
  }
}
