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
 * Diff mode, as far as a host with a diff *view* can take it.
 *
 * The engine's whole share of it is deciding which two files go into that view, so that is what
 * these check. `:diffthis` is the interesting one: Vim turns a window mode on and compares every
 * window that has it, and neither host has a window mode - so the first `:diffthis` remembers the
 * file and the second opens the view over the pair. Same two files at the end, different road.
 *
 * `:diffget` and `:diffput` report `E319` and are checked here beside the ones that work, because
 * the line between them is the point: opening a view over two files is something both hosts do, and
 * reaching into that view hunk by hunk is something neither lets an extension do at all.
 */
class HeadlessDiffTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val windows: HeadlessWindowGroup get() = injector.window as HeadlessWindowGroup
    val diffs: List<Pair<String, String>> get() = windows.diffs

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

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // `:diffsplit`

  @Test
  fun `test diffsplit compares this file with the one it was given`() {
    val s = session()
    s.run("diffsplit other.txt")

    assertEquals(listOf(s.editor.getPath()!! to "/work/other.txt"), s.diffs)
  }

  @Test
  fun `test an absolute name is not resolved against the workspace`() {
    val s = session()
    s.run("diffsplit /elsewhere/other.txt")

    assertEquals("/elsewhere/other.txt", s.diffs.single().second)
  }

  @Test
  fun `test diffsplit with no file is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("diffsplit")

    assertTrue(s.messages.lastError?.contains("E471") == true, "got ${s.messages.lastError}")
    assertEquals(emptyList(), s.diffs)
  }

  /** A host that could not open one has to say so rather than appear to have worked. */
  @Test
  fun `test a host that refuses says so`() {
    val s = session()
    s.windows.canShowDiff = false
    s.messages.clearError()
    s.run("diffsplit other.txt")

    assertTrue(s.messages.lastError?.contains("could not open a diff") == true, "got ${s.messages.lastError}")
  }

  // `:diffthis`, which takes two goes.

  @Test
  fun `test the first diffthis opens nothing and says what it is waiting for`() {
    val s = session()
    s.run("diffthis")

    assertEquals(emptyList(), s.diffs, "there is nothing to compare against yet")
    assertTrue(s.messages.getStatusBarMessage()?.contains("Marked for diffing") == true, "got ${s.messages.getStatusBarMessage()}")
  }

  @Test
  fun `test the second diffthis opens the view over the pair`() {
    val s = session()
    s.run("diffthis")

    // A second file, marked from an editor of its own - which is what `:diffthis` in another
    // window is.
    val second = TestVimCaret(0, isPrimary = true)
    val other = TestVimEditor("other", listOf(second), path = "/work/other.txt").also { second.editorRef = it }
    injector.vimscriptExecutor.execute(
      "diffthis", other, HeadlessExecutionContext, skipHistory = true, indicateErrors = true, CommandLineVimLContext,
    )

    assertEquals(listOf(s.editor.getPath()!! to "/work/other.txt"), s.diffs)
  }

  /** A second `:diffthis` in the same file is still the first half, not a diff of a file with itself. */
  @Test
  fun `test marking the same file twice does not compare it with itself`() {
    val s = session()
    s.run("diffthis")
    s.run("diffthis")

    assertEquals(emptyList(), s.diffs)
  }

  /**
   * The pair is forgotten once it is used, so a third `:diffthis` starts a new one rather than
   * joining the finished one.
   */
  @Test
  fun `test a finished pair is not joined by the next diffthis`() {
    val s = session()
    s.run("diffthis")
    s.run("diffsplit other.txt")
    s.run("diffthis")

    assertEquals(1, s.diffs.size, "the marked file should not still be waiting: ${s.diffs}")
  }

  @Test
  fun `test diffoff forgets a file that was waiting for a pair`() {
    val s = session()
    s.run("diffthis")
    s.run("diffoff")

    val second = TestVimCaret(0, isPrimary = true)
    val other = TestVimEditor("other", listOf(second), path = "/work/other.txt").also { second.editorRef = it }
    injector.vimscriptExecutor.execute(
      "diffthis", other, HeadlessExecutionContext, skipHistory = true, indicateErrors = true, CommandLineVimLContext,
    )

    assertEquals(emptyList(), s.diffs, "a stray :diffthis must not pair with the next file visited")
  }

  /** Accepted rather than refused: the state it exists to restore is the state a host view is in. */
  @Test
  fun `test diffupdate is accepted in silence`() {
    val s = session()
    s.messages.clearError()

    assertEquals(ExecutionResult.Success, s.run("diffupdate"))
    assertEquals(null, s.messages.lastError)
  }

  // The two that need what neither host has.

  @Test
  fun `test diffget and diffput report E319`() {
    val s = session()
    for (line in listOf("diffget", "diffput")) {
      s.messages.clearError()
      s.run(line)
      assertTrue(s.messages.lastError?.contains("E319") == true, "`:$line` gave ${s.messages.lastError}")
    }
  }
}
