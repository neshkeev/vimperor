/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.autocmd.AutoCmdEvent
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:bufdo` and its three relatives, and `:doautocmd`, on both targets.
 *
 * Vim has four `do` commands because it has four lists - buffers, windows, tab pages, arguments -
 * and this fork has one, so the four are the same walk over the editors that are open. What has to
 * be true of that walk is that it reaches every editor rather than only the one the command was
 * typed in, and that it stops when something goes wrong, which is Vim's rule and the one that keeps
 * `:bufdo` from doing half a job nine more times.
 */
class HeadlessForEachEditorTest {

  private class Session(vararg texts: String) {
    val editors: List<TestVimEditor> = texts.map { text ->
      val caret = TestVimCaret(0, isPrimary = true)
      TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    }
    val output: List<String>
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.map { it.trimEnd('\n') }

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editors.first(),
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(vararg texts: String): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(*texts)
  }

  @Test
  fun `test bufdo runs the command in every editor`() {
    val s = session("one apple", "another apple")

    s.run("bufdo s/apple/pear/")

    assertEquals(listOf("one pear", "another pear"), s.editors.map { it.text })
  }

  /** The other three are the same walk, and have to stay the same walk. */
  @Test
  fun `test windo tabdo and argdo walk the same list`() {
    for (command in listOf("windo", "tabdo", "argdo")) {
      val s = session("one apple", "another apple")
      s.run("$command s/apple/pear/")
      assertEquals(listOf("one pear", "another pear"), s.editors.map { it.text }, "`:$command` should have")
    }
  }

  /** Vim stops at the first failure rather than working through the rest. */
  @Test
  fun `test a failing command stops the walk`() {
    val s = session("one apple", "another apple")

    assertEquals(ExecutionResult.Error, s.run("bufdo set nosuchoptionatall"))
  }

  /** The command runs against each editor's own text, not against the one it was typed in. */
  @Test
  fun `test each editor is edited on its own terms`() {
    val s = session("aaa", "bbb")

    s.run("bufdo s/./X/")

    assertEquals(listOf("Xaa", "Xbb"), s.editors.map { it.text })
  }

  @Test
  fun `test a do command with nothing to do is an error`() {
    val s = session("one")

    s.run("bufdo")

    assertTrue((injector.messages as HeadlessMessages).lastError?.contains("E471") == true)
  }

  // `:doautocmd`

  @Test
  fun `test doautocmd fires the event by hand`() {
    val s = session("one")
    injector.autoCmd.registerEventCommand("""echo "fired"""", AutoCmdEvent.BufReadPost)

    s.run("doautocmd BufRead")

    assertEquals(listOf("fired"), s.output)
  }

  /** Vim's event names are case-insensitive; the engine's enum is not, so the command has to be. */
  @Test
  fun `test the event name is matched without regard to case`() {
    val s = session("one")
    injector.autoCmd.registerEventCommand("""echo "fired"""", AutoCmdEvent.BufReadPost)

    s.run("doautocmd bufread")

    assertEquals(listOf("fired"), s.output)
  }

  /** A group name in front of the event is accepted and skipped over. */
  @Test
  fun `test a group before the event is not mistaken for one`() {
    val s = session("one")
    injector.autoCmd.registerEventCommand("""echo "fired"""", AutoCmdEvent.BufReadPost)

    s.run("doautocmd mygroup BufRead")

    assertEquals(listOf("fired"), s.output)
  }

  @Test
  fun `test an event nobody has is E216`() {
    val s = session("one")

    s.run("doautocmd NoSuchEvent")

    assertTrue((injector.messages as HeadlessMessages).lastError?.contains("E216") == true)
  }
}
