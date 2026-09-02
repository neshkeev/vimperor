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
 * `:left`, `:right`, `:center`, `:retab`, `:number`, `:list` and `:z` - the ex commands that work
 * on the text rather than on the editor around it.
 *
 * All of them are engine work with nothing host-shaped in them, which is why they are tested here
 * and on both targets: a buffer, a range, and an answer that is a string.
 */
class HeadlessTextCommandTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val printed: String
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n").trimEnd('\n')

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

  private fun session(text: String): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // `:left`

  @Test
  fun `test left moves the text to the first column`() {
    val s = session("        indented")
    s.run("left")

    assertEquals("indented", s.editor.text)
  }

  @Test
  fun `test left with a count indents by that much`() {
    val s = session("indented")
    s.run("left 4")

    assertEquals("    indented", s.editor.text)
  }

  @Test
  fun `test a range picks the lines`() {
    val s = session("    one\n    two\n    three")
    s.run("2left")

    assertEquals("    one\ntwo\n    three", s.editor.text)
  }

  /**
   * Lines are rewritten last-first, so the offsets of the ones still to do stay where they were.
   *
   * A forward walk gets the first line right and then computes every later line's start from a
   * buffer that has already moved - which is a bug that only shows up on the second line, and only
   * when the indent actually changed width.
   */
  @Test
  fun `test every line of a range is realigned`() {
    val s = session("        one\n        two\n        three")
    s.run("%left")

    assertEquals("one\ntwo\nthree", s.editor.text)
  }

  // `:right` and `:center`

  @Test
  fun `test right pushes the line out to the margin`() {
    val s = session("abc")
    s.run("right 10")

    assertEquals("       abc", s.editor.text)
  }

  @Test
  fun `test center puts half the leftover in front`() {
    val s = session("abc")
    s.run("center 11")

    assertEquals("    abc", s.editor.text)
  }

  /** Nothing is set, so the margin is Vim's fallback of 80. */
  @Test
  fun `test the margin defaults to eighty`() {
    val s = session("abc")
    s.run("right")

    assertEquals(80, s.editor.text.length)
  }

  /** A blank line stays blank rather than being padded out to the margin. */
  @Test
  fun `test a blank line is left alone`() {
    val s = session("abc\n\ndef")
    s.run("%center 11")

    assertEquals("    abc\n\n    def", s.editor.text)
  }

  @Test
  fun `test the existing indent is replaced rather than added to`() {
    val s = session("      abc")
    s.run("center 11")

    assertEquals("    abc", s.editor.text)
  }

  // `:retab`

  /**
   * A run of whitespace is a jump to the next tabstop, not a fixed number of spaces.
   *
   * With the default tabstop of eight, the tab after `ab` reaches column 8, so laying the same line
   * out again produces one tab - and after `abcdefghi`, which is already past 8, it reaches 16.
   */
  @Test
  fun `test retab lays a line out again for the current tabstop`() {
    val s = session("ab\tc")
    s.run("retab")

    assertEquals("ab\tc", s.editor.text)
  }

  @Test
  fun `test retab with a width lays it out for that width`() {
    val s = session("ab\tc")
    s.run("retab 4")

    // The tab reached column 8 at a tabstop of 8; at 4 that is two tabs.
    assertEquals("ab\t\tc", s.editor.text)
  }

  /** A run with no tab in it is left alone, so a file aligned with spaces is not reflowed. */
  @Test
  fun `test a run of spaces is not touched without the bang`() {
    val s = session("ab        c")
    s.run("retab")

    assertEquals("ab        c", s.editor.text)
  }

  /** ...and the bang is how you say you meant it. */
  @Test
  fun `test the bang rewrites every run`() {
    val s = session("ab        c")
    s.run("retab!")

    // Eight spaces from column 2 reach column 10, which is one tab to 8 and two spaces.
    assertEquals("ab\t  c", s.editor.text)
  }

  @Test
  fun `test a width that is not a positive number is reported`() {
    val s = session("ab\tc")
    s.run("retab 0")

    assertTrue(s.messages.lastError?.contains("E487") == true, "got ${s.messages.lastError}")
  }

  // `:number`, `:list` and `:z`

  @Test
  fun `test number prints the lines numbered`() {
    val s = session("one\ntwo")
    s.run("%number")

    assertEquals("1 one\n2 two", s.printed)
  }

  @Test
  fun `test list shows where each line ends`() {
    val s = session("one\ntwo")
    s.run("%list")

    assertEquals("one$\ntwo$", s.printed)
  }

  @Test
  fun `test list shows a tab as caret I`() {
    val s = session("a\tb")
    s.run("list")

    assertEquals("a^Ib$", s.printed)
  }

  @Test
  fun `test z prints a window of lines from the addressed one`() {
    val s = session("one\ntwo\nthree\nfour")
    s.run("2z 2")

    assertEquals("two\nthree", s.printed)
  }

  @Test
  fun `test a z mark is reported rather than taken for the plain form`() {
    val s = session("one\ntwo")
    s.run("z-")

    assertTrue(s.messages.lastError?.contains("E488") == true, "got ${s.messages.lastError}")
  }
}
