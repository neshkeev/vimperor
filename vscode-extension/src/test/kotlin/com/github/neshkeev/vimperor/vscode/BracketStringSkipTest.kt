/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.group.getStringAtPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which brackets a scan skips for being inside a string, and the one question that has to be asked to decide.
 *
 * A bracket written inside a string is text, not a bracket, so `%` and every block text object ask the host whether
 * an offset is inside one. This host used to answer with `findBlockQuoteInLineRange`, which implements `i"` - and
 * `i"` answers something else. With the cursor *between* two strings on a line, Vim's `i"` selects from the previous
 * quote to the next one, across a gap that is not a string at all; `testDeleteDoubleQuotedStringBetweenEvenNumberOfQuotes`
 * in IdeaVim's own suite asserts it, so it is Vim and not a bug. Asked "is this offset inside a string" it therefore
 * said yes for every bracket sitting between two strings, and a scan skipped those brackets without counting them.
 *
 * The answer was already in the engine, unused by this host: `getSingleQuotesRangeNoPSI` and its double-quoted twin
 * walk the line tracking whether they are inside a string, which is the question. IdeaVim calls the same two.
 */
class BracketStringSkipTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
    }

    fun percentFrom(offset: Int): Int {
      editor.primaryCaret().moveToOffset(offset)
      host.type(fake, "%")
      return host.editorFor(fake).primaryCaret().offset
    }
  }

  // ---- the reported case -----------------------------------------------------------------------

  /**
   * The bug as reported, on SQL. `%` on the `(` that opens the CTE landed on the `)` that closes `read_csv` two
   * lines down, because the `(` opening it had been read as quoted - it sits between `'message'` and `'logs.csv'`.
   */
  @Test
  fun `test percent over lines holding two strings each finds the real pair`() {
    val doc = listOf(
      "with events(pod,val) as (",
      "select log->>'pod', log->>'event.original'->>'message' from read_csv('logs.csv', column_names=['log'])",
      "union all",
      "select log->>'pod', log->>'event.original'->>'message' from read_csv('new_logs.csv', column_names=['log'])",
      ")",
      " select 1 ;",
    ).joinToString("\n") + "\n"
    val session = Session(doc)

    val got = session.percentFrom(doc.indexOf("as (") + 3)

    assertEquals(doc.lastIndexOf(')'), got, "landed on ${got} (${doc.substring(got, got + 1)})")
  }

  /** The same thing distilled: one line, two strings, a bracket pair between them. */
  @Test
  fun `test a bracket between two strings is counted`() {
    val doc = "a(\n'x' + f('y')\n)\n"
    val session = Session(doc)

    assertEquals(doc.lastIndexOf(')'), session.percentFrom(doc.indexOf('(')))
  }

  @Test
  fun `test an offset between two strings is not inside a string`() {
    val session = Session("x = 'a' + f('b')\n")

    assertNull(getStringAtPos(session.editor, "x = 'a' + f".length, false), "the ( of f( is between two strings")
  }

  // ---- what must keep working ------------------------------------------------------------------

  /** The claim the host's KDoc makes, and the reason it skips strings at all. */
  @Test
  fun `test a bracket inside a string is still skipped`() {
    val doc = "f(\"(\", x)\n"
    val session = Session(doc)

    assertEquals(doc.indexOf(", x)") + 3, session.percentFrom(doc.indexOf('(')))
  }

  @Test
  fun `test a bracket inside a single-quoted string is still skipped`() {
    val doc = "f('(', x)\n"
    val session = Session(doc)

    assertEquals(doc.indexOf(", x)") + 3, session.percentFrom(doc.indexOf('(')))
  }

  /**
   * And `i"` is left alone, because it is right.
   *
   * This is the test that stops the bug being "fixed" in the wrong place a second time - the first attempt here
   * changed `findBlockQuoteInLineRange` to treat a cursor outside a string as Vim's `in_quote` does, which broke
   * three of IdeaVim's own fixtures. Vim's `i"` really does reach from the previous quote to the next one.
   */
  @Test
  fun `test inner quote from between two strings still spans the gap, as Vim does`() {
    val doc = "x = 'a' + f('b')\n"
    val session = Session(doc)

    session.editor.primaryCaret().moveToOffset(doc.indexOf("f(") + 1)
    session.host.type(session.fake, "di'")

    // The gap is ` + f(`, between the closing quote of 'a' and the opening quote of 'b'.
    assertEquals("x = 'a''b')\n", session.fake.document.content)
  }
}
