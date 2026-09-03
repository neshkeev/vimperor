/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.changelist.VimChangeList
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The change list - `g;`, `g,` and `:changes` - on a host that has no recorder of its own.
 *
 * The list used to be an IntelliJ application service, fed by the platform's `RecentPlacesListener`
 * over an RPC topic, and `g;` in the VS Code host answered `E664: changelist is empty` for the life
 * of the session. The list is engine state now and there are two ways into it: IntelliJ keeps its
 * listener, because the platform sees changes the engine never made, and every other host records
 * the `.` mark - which is Vim's own definition of what the changelist holds.
 *
 * This is the second of those two paths, and the interesting things about it are the rules that are
 * not obvious: a second change on the same line replaces the entry rather than adding one, and the
 * walk starts *past* the newest entry so that the first `g;` lands on it.
 */
class HeadlessChangeListTest {

  private class Session(text: String = "one\ntwo\nthree\nfour\nfive") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val printed: String
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n").trimEnd('\n')

    /** A change on [line], recorded the way every edit records one: by setting the `.` mark. */
    fun changeLine(line: Int, column: Int = 0) {
      injector.markService.setMark(caret, '.', editor.bufferPositionToOffset(bufferPosition(line, column)))
    }

    private fun bufferPosition(line: Int, column: Int) =
      com.maddyhome.idea.vim.api.BufferPosition(line, column, false)

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

  private fun session(text: String = "one\ntwo\nthree\nfour\nfive"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // Recording.

  @Test
  fun `test setting the change mark puts a place on the list`() {
    val s = session()
    s.changeLine(2)

    val changes = VimChangeList.getChanges(s.editor.projectId)
    assertEquals(1, changes.size)
    assertEquals(2, changes.single().line)
  }

  /**
   * Vim merges a change into the newest entry when it is on the same line, which is what keeps
   * typing a word from leaving one entry per keystroke.
   */
  @Test
  fun `test a second change on the same line replaces the entry`() {
    val s = session()
    s.changeLine(2, column = 0)
    s.changeLine(2, column = 2)

    val changes = VimChangeList.getChanges(s.editor.projectId)
    assertEquals(1, changes.size, "two changes on one line are one place")
    assertEquals(2, changes.single().col, "and the place is where the newer one was")
  }

  @Test
  fun `test changes on different lines are different places`() {
    val s = session()
    s.changeLine(0)
    s.changeLine(3)

    assertEquals(listOf(0, 3), VimChangeList.getChanges(s.editor.projectId).map { it.line })
  }

  /** `:keepjumps` is "the jumplist, the alternate file mark and the changelist are not changed". */
  @Test
  fun `test keepjumps records nothing`() {
    val s = session()
    injector.jumpService.recordingSuppressed = true
    s.changeLine(2)
    injector.jumpService.recordingSuppressed = false

    assertEquals(emptyList(), VimChangeList.getChanges(s.editor.projectId).map { it.line })
  }

  // Walking.

  /**
   * The index sits past the newest entry until something walks, so the first `g;` goes *to* the
   * newest change rather than to the one before it. Getting this wrong makes `g;` skip the edit you
   * just made, which is the one everybody uses it for.
   */
  @Test
  fun `test the first step back lands on the newest change`() {
    val s = session()
    s.changeLine(1)
    s.changeLine(4)

    val result = VimChangeList.goToChange(s.editor.projectId, -1)
    assertTrue(result is VimChangeList.MoveResult.At, "got $result")
    assertEquals(4, result.change.line)
  }

  @Test
  fun `test stepping back again lands on the one before it`() {
    val s = session()
    s.changeLine(1)
    s.changeLine(4)

    VimChangeList.goToChange(s.editor.projectId, -1)
    val result = VimChangeList.goToChange(s.editor.projectId, -1)
    assertEquals(1, (result as VimChangeList.MoveResult.At).change.line)
  }

  /**
   * Both ends, and the asymmetry between them is Vim's rather than a slip.
   *
   * `movechangelist` in Vim's `mark.c` clamps into the list before it complains, and only complains
   * when the index is *already* at the end it was asked to pass. So the first `g,` on a list nobody
   * has walked lands on the newest entry - the same place the first `g;` lands - and it takes a
   * second one to say `E663`.
   */
  @Test
  fun `test walking off either end says which end`() {
    val s = session()
    s.changeLine(1)
    s.changeLine(3)

    assertTrue(VimChangeList.goToChange(s.editor.projectId, 1) is VimChangeList.MoveResult.At)
    assertEquals(VimChangeList.MoveResult.AtEnd, VimChangeList.goToChange(s.editor.projectId, 1))

    VimChangeList.goToChange(s.editor.projectId, -1)
    assertEquals(VimChangeList.MoveResult.AtStart, VimChangeList.goToChange(s.editor.projectId, -1))
  }

  @Test
  fun `test an empty list says so rather than answering with a place`() {
    val s = session()

    assertEquals(VimChangeList.MoveResult.Empty, VimChangeList.goToChange(s.editor.projectId, -1))
  }

  // `:changes`

  @Test
  fun `test changes prints the places with the line as it is now`() {
    val s = session()
    s.changeLine(1)
    s.changeLine(4)
    s.run("changes")

    val lines = s.printed.lines()
    assertEquals("change line  col text", lines.first())
    assertTrue(lines.any { it.endsWith("two") }, "the first change's line, as it reads now: ${s.printed}")
    assertTrue(lines.any { it.endsWith("five") }, "and the second's: ${s.printed}")
  }

  /**
   * A list nobody has walked has its marker on a line of its own, past the newest entry - which is
   * the only way to tell "not walking" from "sitting on the newest", and those two behave
   * differently under the next `g;`.
   */
  @Test
  fun `test the marker sits past the end until something walks`() {
    val s = session()
    s.changeLine(1)
    s.run("changes")

    assertEquals(">", s.printed.lines().last())
  }

  @Test
  fun `test the marker moves onto the entry that was walked to`() {
    val s = session()
    s.changeLine(1)
    s.changeLine(4)
    VimChangeList.goToChange(s.editor.projectId, -1)
    s.run("changes")

    val marked = s.printed.lines().single { it.startsWith(">") }
    assertTrue(marked.endsWith("five"), "the marker belongs on the entry g; walked to: ${s.printed}")
    assertTrue(s.printed.lines().last() != ">", "and not also past the end")
  }

  @Test
  fun `test the numbers count away from where the walk is`() {
    val s = session()
    s.changeLine(0)
    s.changeLine(2)
    s.changeLine(4)
    VimChangeList.goToChange(s.editor.projectId, -1)
    s.run("changes")

    // Walked to the newest, so the older two are one and two steps further back.
    val numbers = s.printed.lines().drop(1).filter { it.isNotBlank() }.map { it.drop(1).take(5).trim() }
    assertEquals(listOf("2", "1", "0"), numbers, "got ${s.printed}")
  }

  @Test
  fun `test an empty change list still prints its header and its marker`() {
    val s = session()
    s.run("changes")

    assertEquals("change line  col text\n>", s.printed)
  }

  @Test
  fun `test changes takes neither a range nor an argument`() {
    val s = session()
    s.messages.clearError()
    s.run("1,2changes")

    assertTrue(s.messages.lastError != null, "a range on `:changes` is an error in Vim too")
  }
}
