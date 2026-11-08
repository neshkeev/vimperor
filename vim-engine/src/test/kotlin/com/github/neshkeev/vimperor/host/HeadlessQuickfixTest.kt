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
import com.maddyhome.idea.vim.host.HeadlessFile
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanel
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.HeadlessVirtualBuffers
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.quickfix.parseQuickfixLines
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.LocationWindow
import com.maddyhome.idea.vim.vimscript.model.commands.QuickfixWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quickfix list, on both targets.
 *
 * All of it is engine work: a list of places, a way to fill it, and a way to walk it. What the host
 * supplies is running a process and opening a file, and both are things it already supplies for
 * `:!` and `:edit` - so the list, the parsing and the stepping can be checked here, and are.
 *
 * The parser is the part most worth pinning. Vim's `'errorformat'` is a pattern language of its own
 * and this reads four shapes instead; a test that only went through `:cexpr` would leave which four
 * unsaid, so they are asserted directly as well.
 */
class HeadlessQuickfixTest {

  private class Session(text: String = "one\ntwo") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val opened: List<String> get() = (injector.file as HeadlessFile).opened
    val printed: String
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n").trimEnd('\n')

    val windows: HeadlessVirtualBuffers
      get() = injector.virtualBufferGroup as HeadlessVirtualBuffers

    val status: String? get() = injector.messages.getStatusBarMessage()

    /** `:cclose` used to close the output panel. [HeadlessOutputPanel.close] records that it was asked. */
    val outputClosed: Boolean
      get() = ((injector.outputPanel as HeadlessOutputPanelService).getCurrentOutputPanel() as HeadlessOutputPanel).closed

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

  private fun session(text: String = "one\ntwo"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // The parser.

  @Test
  fun `test a line with a file, a line and a column is read as all three`() {
    val entry = parseQuickfixLines(listOf("src/Main.kt:12:5: error: nope")).single()

    assertEquals("src/Main.kt", entry.path)
    assertEquals(12, entry.line)
    assertEquals(5, entry.column)
    assertEquals("error: nope", entry.text)
  }

  /** `grep -n` prints no column, which is the second of the four shapes. */
  @Test
  fun `test a line with no column is read with column one`() {
    val entry = parseQuickfixLines(listOf("notes.txt:3:the line itself")).single()

    assertEquals("notes.txt", entry.path)
    assertEquals(3, entry.line)
    assertEquals(1, entry.column)
  }

  @Test
  fun `test the parenthesised form is read too`() {
    val entry = parseQuickfixLines(listOf("Main.kt(7,2): warning: hm")).single()

    assertEquals("Main.kt", entry.path)
    assertEquals(7, entry.line)
    assertEquals(2, entry.column)
  }

  /**
   * A Windows path has a colon in the file name, which is why the shapes are not one regex over
   * every colon in the line.
   */
  @Test
  fun `test a drive letter is part of the file name`() {
    val entry = parseQuickfixLines(listOf("""C:\src\Main.kt:12:5: error""")).single()

    assertEquals("""C:\src\Main.kt""", entry.path)
    assertEquals(12, entry.line)
  }

  /** A line nothing can be made of is kept, so a message spread over several lines still shows. */
  @Test
  fun `test an unparsed line is kept without a file`() {
    val entry = parseQuickfixLines(listOf("   ^ here")).single()

    assertNull(entry.path)
    assertEquals("   ^ here", entry.text)
  }

  // Filling and walking.

  @Test
  fun `test cexpr fills the list and goes to the first entry`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")

    assertEquals(listOf("a.kt"), s.opened)
  }

  @Test
  fun `test cnext walks to the entry after it`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    s.run("cnext")

    assertEquals(listOf("a.kt", "b.kt"), s.opened)
  }

  /** Lines with no file behind them are stepped over rather than counted. */
  @Test
  fun `test cnext skips the lines that are not places`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\n   ^ pointing at it\nb.kt:2:1: second"""")
    s.run("cnext")

    assertEquals(listOf("a.kt", "b.kt"), s.opened)
  }

  @Test
  fun `test clast goes to the end and cfirst comes back`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second\nc.kt:3:1: third"""")
    s.run("clast")
    s.run("cfirst")

    assertEquals(listOf("a.kt", "c.kt", "a.kt"), s.opened)
  }

  /** `:cc {n}` is Vim's one-based numbering, which is what `:clist` prints. */
  @Test
  fun `test cc goes to the numbered entry`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    s.run("cc 2")

    assertEquals(listOf("a.kt", "b.kt"), s.opened)
  }

  @Test
  fun `test walking past the end is an error rather than a wrap`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: only one"""")
    s.messages.clearError()
    s.run("cnext")

    assertTrue(s.messages.lastError?.contains("E42") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test clist prints the list with a marker on the current entry`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:3: second"""")
    s.run("clist")

    assertEquals(">1 a.kt:1 col 1: first\n 2 b.kt:2 col 3: second", s.printed)
    assertEquals(0, s.windows.openCount, "printing the list must not open a window")
  }

  @Test
  fun `test an empty list is E42 rather than an empty table`() {
    val s = session()
    s.run("clist")

    assertTrue(s.messages.lastError?.contains("E42") == true, "got ${s.messages.lastError}")
    assertFalse(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow))
  }

  /** `:caddexpr` adds to the list instead of replacing it, and does not jump. */
  @Test
  fun `test caddexpr adds without jumping`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first"""")
    s.run("""caddexpr "b.kt:2:1: second"""")
    s.run("clist")

    assertEquals(listOf("a.kt"), s.opened, "adding should not have jumped anywhere")
    assertEquals(">1 a.kt:1 col 1: first\n 2 b.kt:2 col 1: second", s.printed)
  }

  /**
   * The location list is a different list, which is the whole difference between the two families.
   *
   * `:lexpr` fills the window's own and `:cnext` must not see it.
   */
  @Test
  fun `test the location list is not the quickfix list`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: quickfix"""")
    s.run("""lexpr "b.kt:1:1: location"""")

    s.run("clist")
    assertEquals(">1 a.kt:1 col 1: quickfix", s.printed)

    (injector.outputPanel as HeadlessOutputPanelService).getCurrentOutputPanel().clearText()
    s.run("llist")
    assertEquals(">1 b.kt:1 col 1: location", s.printed)
  }

  @Test
  fun `test cbuffer reads the buffer it is typed in`() {
    val s = session("a.kt:4:2: from the buffer")
    s.run("cbuffer")

    assertEquals(listOf("a.kt"), s.opened)
  }

  // ---- files, not entries -----------------------------------------------------------------------

  /** a:1, a:2, b:1, c:1. `:cnfile` is the first of the next file; `:cpfile` is the last of the previous. */
  @Test
  fun `test cnfile and cpfile walk files`() {
    val lines = """a.kt:1:1: a1\na.kt:2:1: a2\nb.kt:1:1: b1\nc.kt:1:1: c1"""

    val forward = session()
    forward.run("""cexpr "$lines"""")
    forward.run("cnfile")
    assertEquals("b1", forward.statusText(), "first of the next file")
    forward.run("cnfile")
    assertEquals("c1", forward.statusText())
    forward.run("cnfile")
    assertTrue(forward.messages.lastError?.contains("E42") == true, "already on the last file: ${forward.messages.lastError}")

    val counted = session()
    counted.run("""cexpr "$lines"""")
    counted.run("2cnfile")
    assertEquals("c1", counted.statusText(), "two files on from a")
    assertNull(counted.messages.lastError)

    val past = session()
    past.run("""cexpr "$lines"""")
    past.run("3cnfile")
    assertEquals("c1", past.statusText(), "a count past the last file stops there")
    assertNull(past.messages.lastError, "stopping on the last file is not an error")

    val back = session()
    back.run("""cexpr "$lines"""")
    back.run("clast")
    back.run("cpfile")
    assertEquals("b1", back.statusText())
    back.run("cpfile")
    assertEquals("a2", back.statusText(), "the last item of a, not the first")
    back.run("cNfile")
    assertTrue(back.messages.lastError?.contains("E42") == true, "already on the first file: ${back.messages.lastError}")

    val fromFirst = session()
    fromFirst.run("""cexpr "$lines"""")
    fromFirst.run("cpfile")
    assertTrue(fromFirst.messages.lastError?.contains("E42") == true, "a:1 is the first file: ${fromFirst.messages.lastError}")
  }

  /** An unparsed line between two files is not a file of its own. */
  @Test
  fun `test cnfile skips a line with no file`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: a\n   not a file\nb.kt:2:1: b"""")
    s.run("cnfile")

    assertEquals("b", s.statusText())
    assertEquals(listOf("a.kt", "b.kt"), s.opened)
  }

  /** The argument and the count prefix are the same count. `:cnfile 2` is how `:cnext` takes one. */
  @Test
  fun `test cnfile reads its count from the argument`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: a\nb.kt:1:1: b\nc.kt:1:1: c"""")
    s.run("cnfile 2")

    assertEquals("c", s.statusText())
  }

  /** `:lnfile` walks the location list. `:cnext` must still be on the quickfix list. */
  @Test
  fun `test lnfile does not move the quickfix list`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: quick\nb.kt:2:1: next"""")
    s.run("""lexpr "z.kt:1:1: loc\ny.kt:2:1: loc2"""")
    s.run("lnfile")
    s.run("cnext")

    assertEquals(listOf("a.kt", "z.kt", "y.kt", "b.kt"), s.opened)
  }

  // ---- the window -------------------------------------------------------------------------------

  @Test
  fun `test copen on an empty list opens an empty buffer`() {
    val s = session()
    val result = s.run("copen")

    assertEquals(ExecutionResult.Success, result)
    assertNull(s.messages.lastError)
    assertEquals("", s.windows.contentOf(QuickfixWindow))
    assertEquals(0, s.windows.caretOf(QuickfixWindow))
  }

  @Test
  fun `test copen puts the caret on the current entry and a second call rewrites it`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    s.run("cnext")
    s.run("copen")

    assertEquals("a.kt:1 col 1: first\nb.kt:2 col 1: second", s.windows.contentOf(QuickfixWindow))
    assertEquals(1, s.windows.caretOf(QuickfixWindow))
    assertEquals(1, s.windows.openCount)

    s.run("""cexpr "c.kt:3:1: other"""")
    s.run("copen")

    assertEquals(1, s.windows.openCount, "a second :copen reuses the buffer")
    assertEquals("c.kt:3 col 1: other", s.windows.contentOf(QuickfixWindow))
    assertEquals(0, s.windows.caretOf(QuickfixWindow))
  }

  @Test
  fun `test cclose drops the buffer and leaves the list`() {
    val s = session()
    s.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    s.run("clist")
    val printed = s.printed
    s.run("copen")
    s.run("cclose")

    assertFalse(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow))
    assertEquals(printed, s.printed, "the output panel is not the window")
    assertFalse(s.outputClosed, ":cclose must not close the output panel")
    s.run("cnext")
    assertEquals(listOf("a.kt", "b.kt"), s.opened, "the list is still there")
  }

  @Test
  fun `test cclose with no window open does nothing`() {
    val s = session()
    val result = s.run("cclose")

    assertEquals(ExecutionResult.Success, result)
    assertNull(s.messages.lastError)
    assertFalse(s.outputClosed)
  }

  @Test
  fun `test cnext updates an open window and does not open a closed one`() {
    val hidden = session()
    hidden.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    hidden.run("cnext")

    assertEquals(0, hidden.windows.openCount)
    assertFalse(hidden.windows.isOpen(HeadlessExecutionContext, QuickfixWindow))

    val shown = session()
    shown.run("""cexpr "a.kt:1:1: first\nb.kt:2:1: second"""")
    shown.run("copen")
    shown.run("cnext")

    assertEquals(1, shown.windows.openCount)
    assertEquals(1, shown.windows.caretOf(QuickfixWindow))
    assertEquals("a.kt:1 col 1: first\nb.kt:2 col 1: second", shown.windows.contentOf(QuickfixWindow))
  }

  @Test
  fun `test cwindow opens for a valid entry and closes when there is none`() {
    val s = session()
    s.run("cwindow")
    assertFalse(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow))

    s.run("""cexpr "a.kt:1:1: first"""")
    s.run("cwindow 10")
    assertEquals("a.kt:1 col 1: first", s.windows.contentOf(QuickfixWindow))

    s.run("""cexpr "not a place"""")
    s.run("copen")
    assertTrue(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow), "copen opens an invalid list")
    s.run("cwindow")
    assertFalse(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow), "cwindow closes a window with nothing valid")
  }

  /** The location window is a different buffer. `:cclose` must not take it. */
  @Test
  fun `test lopen is not the quickfix window`() {
    val s = session()
    s.run("""lexpr "a.kt:1:1: loc"""")
    s.run("lopen")
    s.run("""cexpr "b.kt:1:1: quick"""")
    s.run("copen")

    assertEquals("a.kt:1 col 1: loc", s.windows.contentOf(LocationWindow))
    assertEquals("b.kt:1 col 1: quick", s.windows.contentOf(QuickfixWindow))
    s.run("cclose")
    assertTrue(s.windows.isOpen(HeadlessExecutionContext, LocationWindow))
    assertFalse(s.windows.isOpen(HeadlessExecutionContext, QuickfixWindow))
  }

  private fun Session.statusText(): String = status?.substringAfterLast(' ') ?: ""
}
