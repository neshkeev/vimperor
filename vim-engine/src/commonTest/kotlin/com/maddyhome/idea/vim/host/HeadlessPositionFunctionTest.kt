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
 * The position family, the two line-writing functions, and the buffer questions.
 *
 * The round trip is what these are for and so it is what is tested: `setpos('.', getpos('.'))` has
 * to be a no-op in code that never looks inside the list, because that is the only reason a plugin
 * can save a cursor and put it back without knowing what a position is made of. The same is true of
 * the pair one level up - move away, restore, and be exactly where you were.
 *
 * `virtcol()` and `indent()` are the two that count *columns* rather than characters, and both are
 * tested against tabs, because with spaces alone they are indistinguishable from `col()` and from
 * counting the leading blanks.
 */
class HeadlessPositionFunctionTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret), path = "/work/Main.kt").also { caret.editorRef = it }

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

  private fun session(text: String = "one two\nthree four\nfive"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // ---- getpos and setpos -----------------------------------------------------------------------

  @Test
  fun `test getpos answers in Vim's four-element shape`() {
    val s = session()
    s.value("cursor(2, 3)")

    assertEquals("[0, 2, 3, 0]", s.value("getpos('.')"))
  }

  /**
   * The reason the pair exists: a plugin saves a position and puts it back without ever looking
   * inside the list it was handed.
   */
  @Test
  fun `test setpos of getpos is a no-op`() {
    val s = session()
    s.value("cursor(2, 5)")
    s.run("""let g:saved = getpos('.')""")
    s.value("cursor(1, 1)")
    s.run("""call setpos('.', g:saved)""")

    assertEquals("[0, 2, 5, 0]", s.value("getpos('.')"))
  }

  @Test
  fun `test setpos can put a mark somewhere`() {
    val s = session()
    s.value("""setpos("'a", [0, 3, 1, 0])""")

    assertEquals("3", s.value("""line("'a")"""))
  }

  @Test
  fun `test getpos of a mark nobody set is all zeroes`() {
    val s = session()

    assertEquals("[0, 0, 0, 0]", s.value("""getpos("'z")"""))
  }

  /** The fifth element is the whole reason `getcurpos()` is not `getpos('.')`. */
  @Test
  fun `test getcurpos carries the column the caret is trying to be in`() {
    val s = session()
    s.value("cursor(1, 7)")

    assertEquals(5, s.value("getcurpos()").split(",").size, "five elements, not four")
  }

  @Test
  fun `test setpos past the end of the buffer is refused`() {
    val s = session()

    assertEquals("-1", s.value("""setpos('.', [0, 99, 1, 0])"""))
  }

  // ---- cursor ----------------------------------------------------------------------------------

  @Test
  fun `test cursor moves by line and column`() {
    val s = session()

    assertEquals("0", s.value("cursor(2, 4)"))
    assertEquals("2", s.value("line('.')"))
    assertEquals("4", s.value("col('.')"))
  }

  /** Zero means "leave that one alone", which is what makes `cursor(0, 5)` a column move. */
  @Test
  fun `test a zero leaves that coordinate where it was`() {
    val s = session()
    s.value("cursor(2, 4)")
    s.value("cursor(0, 2)")

    assertEquals("2", s.value("line('.')"))
    assertEquals("2", s.value("col('.')"))
  }

  @Test
  fun `test cursor also takes a list`() {
    val s = session()

    assertEquals("0", s.value("cursor([2, 3])"))
    assertEquals("2", s.value("line('.')"))
  }

  @Test
  fun `test cursor past the end is refused`() {
    val s = session()

    assertEquals("-1", s.value("cursor(99, 1)"))
  }

  // ---- columns ---------------------------------------------------------------------------------

  /**
   * The difference between a character column and a screen column, which only tabs make visible.
   *
   * Two tabs and a letter is three characters and seventeen columns at the default `'tabstop'`.
   * With spaces alone this function is indistinguishable from `col()`.
   */
  @Test
  fun `test virtcol counts a tab as a jump to the next stop`() {
    val s = session("\t\tx")
    s.value("cursor(1, 3)")

    assertEquals("3", s.value("col('.')"))
    assertEquals("17", s.value("virtcol('.')"))
  }

  @Test
  fun `test virtcol with no tabs is the character column`() {
    val s = session()
    s.value("cursor(1, 4)")

    assertEquals("4", s.value("virtcol('.')"))
  }

  @Test
  fun `test indent measures in columns so tabs and spaces compare`() {
    val s = session("\tone\n        two\nthree")

    assertEquals("8", s.value("indent(1)"), "one tab at the default tabstop")
    assertEquals("8", s.value("indent(2)"), "eight spaces is the same indent")
    assertEquals("0", s.value("indent(3)"))
  }

  @Test
  fun `test indent of a line that is not there is minus one`() {
    val s = session()

    assertEquals("-1", s.value("indent(99)"))
  }

  // ---- byte and character indices ----------------------------------------------------------------

  /**
   * The two directions of the same conversion, which exist because Vim's string functions count
   * bytes and its cursor functions count characters.
   */
  @Test
  fun `test byteidx and charidx convert between the two counts`() {
    val s = session()

    assertEquals("0", s.value("""byteidx('héllo', 0)"""))
    assertEquals("1", s.value("""byteidx('héllo', 1)"""))
    assertEquals("3", s.value("""byteidx('héllo', 2)"""), "the accented character is two bytes")
    assertEquals("2", s.value("""charidx('héllo', 3)"""))
  }

  @Test
  fun `test an index past the end is minus one`() {
    val s = session()

    assertEquals("-1", s.value("""byteidx('abc', 9)"""))
    assertEquals("-1", s.value("""charidx('abc', 9)"""))
  }

  // ---- writing lines ---------------------------------------------------------------------------

  /** Writing to the buffer without a register, a motion or a mode. */
  @Test
  fun `test setline replaces a line`() {
    val s = session()

    assertEquals("0", s.value("""setline(2, 'replaced')"""))
    assertEquals("one two\nreplaced\nfive", s.editor.text)
  }

  @Test
  fun `test setline takes a list of lines`() {
    val s = session()
    s.value("""setline(1, ['first', 'second'])""")

    assertEquals("first\nsecond\nfive", s.editor.text)
  }

  /** Vim stops at the end rather than growing the buffer - `append()` is the one that grows it. */
  @Test
  fun `test setline past the end fails rather than adding a line`() {
    val s = session()

    assertEquals("1", s.value("""setline(99, 'nope')"""))
    assertEquals("one two\nthree four\nfive", s.editor.text)
  }

  @Test
  fun `test append puts lines after the one named`() {
    val s = session()

    assertEquals("0", s.value("""append(1, 'added')"""))
    assertEquals("one two\nadded\nthree four\nfive", s.editor.text)
  }

  /** Line zero is "before the first line", which is how a config prepends. */
  @Test
  fun `test append to line zero prepends`() {
    val s = session()
    s.value("""append(0, 'first')""")

    assertEquals("first\none two\nthree four\nfive", s.editor.text)
  }

  @Test
  fun `test append at the end of the buffer`() {
    val s = session()
    s.value("""append(3, 'last')""")

    assertEquals("one two\nthree four\nfive\nlast\n", s.editor.text)
  }

  @Test
  fun `test append takes a list too`() {
    val s = session()
    s.value("""append(0, ['a', 'b'])""")

    assertEquals("a\nb\none two\nthree four\nfive", s.editor.text)
  }

  // ---- buffers ---------------------------------------------------------------------------------

  @Test
  fun `test bufname of the current buffer is its path`() {
    val s = session()

    assertEquals("/work/Main.kt", s.value("bufname('%')"))
    assertEquals("/work/Main.kt", s.value("bufname()"))
  }

  @Test
  fun `test the buffer existence questions agree about this one`() {
    val s = session()

    assertEquals("1", s.value("bufexists('%')"))
    assertEquals("1", s.value("buflisted('%')"))
    assertEquals("1", s.value("bufloaded('%')"))
    assertEquals("0", s.value("bufexists('nosuchfile')"))
  }

  @Test
  fun `test getbufline reads from the buffer on screen`() {
    val s = session()

    assertEquals("['three four']", s.value("getbufline('%', 2)"))
    assertEquals("['one two', 'three four']", s.value("getbufline('%', 1, 2)"))
  }

  /** A buffer this fork cannot see is an empty list, which is Vim's answer for one not loaded. */
  @Test
  fun `test getbufline of a buffer nobody has open is empty`() {
    val s = session()

    assertEquals("[]", s.value("getbufline('nosuchfile', 1)"))
  }

  @Test
  fun `test the window questions answer about the one you are in`() {
    val s = session()

    assertEquals("1", s.value("winnr()"))
    assertEquals("1", s.value("tabpagenr()"))
    assertTrue(s.value("winheight(0)").toInt() >= 1)
  }
}
