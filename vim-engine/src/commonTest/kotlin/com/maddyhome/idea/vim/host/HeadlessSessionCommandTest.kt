/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:ball`, `:behave` and `:scriptnames` - three small commands about the session rather than the
 * buffer.
 *
 * They have nothing in common except that, which is why they are together: each is a handful of
 * lines and none of them deserves a file of tests to itself.
 */
class HeadlessSessionCommandTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem
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

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // `:behave`

  /** Vim defines `:behave` as exactly these settings and nothing more. */
  @Test
  fun `test behave mswin sets the four options Vim sets`() {
    val s = session()
    s.run("behave mswin")

    assertEquals("mouse,key", injector.options(s.editor).selectmode.joinToString(","))
    assertEquals("startsel,stopsel", injector.options(s.editor).keymodel.joinToString(","))
    assertEquals("exclusive", injector.options(s.editor).selection)
  }

  @Test
  fun `test behave xterm puts them back`() {
    val s = session()
    s.run("behave mswin")
    s.run("behave xterm")

    assertEquals("", injector.options(s.editor).selectmode.joinToString(","))
    assertEquals("", injector.options(s.editor).keymodel.joinToString(","))
    assertEquals("inclusive", injector.options(s.editor).selection)
  }

  @Test
  fun `test behave anything else is an error rather than a silent no-op`() {
    val s = session()
    s.messages.clearError()
    s.run("behave sensibly")

    assertTrue(s.messages.lastError?.contains("E475") == true, "got ${s.messages.lastError}")
  }

  // `:scriptnames`

  @Test
  fun `test scriptnames lists what was sourced, numbered from one`() {
    val s = session()
    s.files.written["/work/one.vim"] = "set scrolloff=3\n"
    s.files.written["/work/two.vim"] = "set scrolloff=4\n"
    injector.vimscriptExecutor.executeFile("/work/one.vim", s.editor, false, true)
    injector.vimscriptExecutor.executeFile("/work/two.vim", s.editor, false, true)
    s.run("scriptnames")

    assertEquals("  1: /work/one.vim\n  2: /work/two.vim", s.printed)
  }

  /**
   * A file that could not be read is still listed, which is the case the command exists for: a
   * config that sources something missing looks exactly like one that never mentioned it.
   */
  @Test
  fun `test a file that failed to load is still listed`() {
    val s = session()
    injector.vimscriptExecutor.executeFile("/work/gone.vim", s.editor, false, false)
    s.run("scriptnames")

    assertTrue("/work/gone.vim" in s.printed, "got ${s.printed}")
  }

  /** The number identifies the script, not the loading of it, so a second source keeps it. */
  @Test
  fun `test sourcing a file twice does not list it twice`() {
    val s = session()
    s.files.written["/work/one.vim"] = "set scrolloff=3\n"
    injector.vimscriptExecutor.executeFile("/work/one.vim", s.editor, false, true)
    injector.vimscriptExecutor.executeFile("/work/one.vim", s.editor, false, true)
    s.run("scriptnames")

    assertEquals("  1: /work/one.vim", s.printed)
  }

  @Test
  fun `test scriptnames on a session that sourced nothing prints nothing`() {
    val s = session()
    s.run("scriptnames")

    assertEquals("", s.printed)
  }

  // `:ball` and its two aliases

  @Test
  fun `test ball opens a window for every buffer`() {
    val s = session()
    (injector.file as HeadlessFile).buffers = listOf("a.kt", "b.kt", "c.kt")
    s.run("ball")

    assertTrue(s.opened.containsAll(listOf("a.kt", "b.kt", "c.kt")), "got ${s.opened}")
  }

  @Test
  fun `test a count limits how many are opened`() {
    val s = session()
    (injector.file as HeadlessFile).buffers = listOf("a.kt", "b.kt", "c.kt")
    s.run("ball 2")

    assertEquals(listOf("a.kt", "b.kt"), s.opened.filter { it.endsWith(".kt") })
  }

  /** Vim documents `:unhide` as "same as :ball", and `:sunhide` as the split form of it. */
  @Test
  fun `test unhide and sunhide do what ball does`() {
    for (command in listOf("unhide", "sunhide", "sball")) {
      val s = session()
      (injector.file as HeadlessFile).buffers = listOf("a.kt")
      s.run(command)

      assertTrue("a.kt" in s.opened, "`:$command` opened ${s.opened}")
    }
  }

  // `:language`, which needs a locale nothing here has.

  @Test
  fun `test language reports that this build does not have it`() {
    val s = session()
    s.messages.clearError()
    s.run("language en_GB")

    assertTrue(s.messages.lastError?.contains("E319") == true, "got ${s.messages.lastError}")
  }
}
