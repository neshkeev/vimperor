/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.quickfix.parseQuickfixLines
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
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
  }

  @Test
  fun `test an empty list is E42 rather than an empty table`() {
    val s = session()
    s.run("clist")

    assertTrue(s.messages.lastError?.contains("E42") == true, "got ${s.messages.lastError}")
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
}
