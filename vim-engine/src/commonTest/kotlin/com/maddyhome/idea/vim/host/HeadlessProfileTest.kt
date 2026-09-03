/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.profile.Profile
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:profile` - how long the Vimscript functions in this session took.
 *
 * The timings themselves cannot be asserted - a millisecond clock on a machine doing other things
 * is not a thing to write an expectation against - so what these check is everything around them:
 * that the right functions are counted, that the count is right, that a function nobody asked to
 * watch is not counted at all, and that "self" is charged to the callee rather than twice.
 *
 * That last one is the reason the profiler is more than a stopwatch. A function that spends its
 * whole life calling another would otherwise appear to be the expensive one, and the report would
 * point at the wrong place every time it mattered.
 */
class HeadlessProfileTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem
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

    /** A function, defined the way a config defines one. */
    fun define(source: String) = run(source.trimIndent().replace("\n", "\n"))
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  private fun Session.defineFunction(name: String, vararg body: String) {
    injector.vimscriptExecutor.execute(
      (listOf("function! $name()") + body + listOf("endfunction")).joinToString("\n"),
      editor,
      HeadlessExecutionContext,
      skipHistory = true,
      indicateErrors = true,
      CommandLineVimLContext,
    )
  }

  @Test
  fun `test a watched function is counted every time it is called`() {
    val s = session()
    s.defineFunction("Slow", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func Slow")

    s.run("call Slow()")
    s.run("call Slow()")
    s.run("call Slow()")

    val entry = Profile.entries().single { it.name == "Slow" }
    assertEquals(3, entry.count)
  }

  /** Profiling everything drowns the one function you were asking about. */
  @Test
  fun `test a function nobody asked to watch is not counted`() {
    val s = session()
    s.defineFunction("Watched", "  let x = 1")
    s.defineFunction("Ignored", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func Watched")

    s.run("call Watched()")
    s.run("call Ignored()")

    assertEquals(listOf("Watched"), Profile.entries().map { it.name })
  }

  @Test
  fun `test a star watches everything, which is Vim's spelling too`() {
    val s = session()
    s.defineFunction("One", "  let x = 1")
    s.defineFunction("Two", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func *")

    s.run("call One()")
    s.run("call Two()")

    assertEquals(setOf("One", "Two"), Profile.entries().map { it.name }.toSet())
  }

  /**
   * The reason this is more than a stopwatch.
   *
   * A function that spends its life calling another would otherwise look like the expensive one,
   * and the report would point at the wrong place exactly when it mattered. The caller's total has
   * to include the callee and its self time has to not.
   */
  @Test
  fun `test time spent in a nested call is not charged to the caller twice`() {
    val s = session()
    s.defineFunction("Inner", "  let x = 1")
    s.defineFunction("Outer", "  call Inner()")
    s.run("profile start /work/report.txt")
    s.run("profile func *")

    s.run("call Outer()")

    val outer = Profile.entries().single { it.name == "Outer" }
    val inner = Profile.entries().single { it.name == "Inner" }
    assertTrue(outer.self <= outer.total, "self cannot exceed total: $outer")
    assertTrue(outer.total >= inner.total, "the caller's total includes the callee's: $outer, $inner")
    assertEquals(1, inner.count)
  }

  // Starting and stopping.

  @Test
  fun `test pause stops counting and continue starts again`() {
    val s = session()
    s.defineFunction("Counted", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func *")

    s.run("call Counted()")
    s.run("profile pause")
    s.run("call Counted()")
    s.run("call Counted()")
    s.run("profile continue")
    s.run("call Counted()")

    assertEquals(2, Profile.entries().single().count, "the two while paused should not be counted")
  }

  @Test
  fun `test nothing is counted before profile start`() {
    val s = session()
    s.defineFunction("Early", "  let x = 1")
    s.run("profile func *")
    s.run("call Early()")

    assertEquals(emptyList(), Profile.entries())
  }

  // The report.

  @Test
  fun `test dump writes the report to the file that start named`() {
    val s = session()
    s.defineFunction("Reported", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func *")
    s.run("call Reported()")
    s.run("profile stop")

    val report = s.files.written["/work/report.txt"]
    assertTrue(report != null, "wrote ${s.files.written.keys}")
    assertTrue("Reported" in report!!, "got $report")
    assertTrue("FUNCTIONS SORTED ON SELF TIME" in report, "Vim's second table: $report")
  }

  /** The reader is in the session that was profiled, so a dump with nowhere to write goes to them. */
  @Test
  fun `test dump with no file prints to the panel`() {
    val s = session()
    s.defineFunction("Printed", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func *")
    s.run("call Printed()")
    s.run("profile dump")

    assertTrue("Printed" in s.printed, "got ${s.printed}")
    assertTrue("count  total (ms)" in s.printed, "got ${s.printed}")
  }

  @Test
  fun `test dump to a named file writes there instead`() {
    val s = session()
    s.defineFunction("Elsewhere", "  let x = 1")
    s.run("profile start /work/report.txt")
    s.run("profile func *")
    s.run("call Elsewhere()")
    s.run("profile dump /work/other.txt")

    assertTrue("/work/other.txt" in s.files.written.keys, "wrote ${s.files.written.keys}")
  }

  @Test
  fun `test a report with nothing in it says so rather than printing an empty table`() {
    val s = session()
    s.run("profile start /work/report.txt")
    s.run("profile dump")

    assertTrue("Nothing was profiled" in s.printed, "got ${s.printed}")
  }

  // Malformed.

  @Test
  fun `test a subcommand Vim does not have is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("profile sideways")

    assertTrue(s.messages.lastError?.contains("E475") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test start and func both need an argument`() {
    val s = session()
    for (line in listOf("profile start", "profile func")) {
      s.messages.clearError()
      s.run(line)
      assertTrue(s.messages.lastError?.contains("E471") == true, "`:$line` gave ${s.messages.lastError}")
    }
  }
}
