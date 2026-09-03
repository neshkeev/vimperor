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
 * `:mkvimrc` and `:mkexrc` - this session written back out as the commands that would recreate it.
 *
 * The point of the command is that the file it writes can be read back, so the assertions here are
 * mostly about the round trip: what comes out has to be commands this same engine accepts, and a
 * mapping that shows in `:map` has to show in the file. It is written from `getAllMappingInfoWithMode`
 * for exactly that reason - one source, so the two cannot drift.
 *
 * The refusal matters as much as the writing. `:mkvimrc` with no `!` over a file that exists is
 * `E189` and *nothing else*: the file it would land on is usually the user's config.
 */
class HeadlessMkVimrcTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem

    /** What `:mkvimrc` wrote to the default place, or null if nothing did. */
    val written: String? get() = files.written["/work/.ideavimrc"]

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

  @Test
  fun `test mkvimrc writes the config the hosts actually read`() {
    val s = session()
    s.run("mkvimrc")

    assertTrue(s.written != null, "wrote ${s.files.written.keys}")
    assertTrue(s.written!!.startsWith("\" Written by :mkvimrc."), "got ${s.written}")
  }

  /** Vim's is `.vimrc`; nothing in this fork reads one, so the default is the file that is read. */
  @Test
  fun `test mkexrc writes exrc and mkvimrc writes ideavimrc`() {
    val s = session()
    s.run("mkexrc")

    assertEquals(setOf("/work/.exrc"), s.files.written.keys)
  }

  @Test
  fun `test a name is taken relative to the working directory`() {
    val s = session()
    s.run("mkvimrc backup.vim")

    assertEquals(setOf("/work/backup.vim"), s.files.written.keys)
  }

  @Test
  fun `test an absolute name is left alone`() {
    val s = session()
    s.run("mkvimrc /elsewhere/config")

    assertEquals(setOf("/elsewhere/config"), s.files.written.keys)
  }

  // What goes in it.

  @Test
  fun `test a mapping comes back out as the command that made it`() {
    val s = session()
    s.run("nnoremap Q db")
    s.run("mkvimrc")

    assertTrue("nnoremap Q db" in s.written!!, "got ${s.written}")
  }

  @Test
  fun `test a recursive mapping is written without nore`() {
    val s = session()
    s.run("nmap Q db")
    s.run("mkvimrc")

    assertTrue("nmap Q db" in s.written!!, "got ${s.written}")
    assertTrue("nnoremap Q" !in s.written!!, "a recursive mapping must not come back non-recursive")
  }

  @Test
  fun `test each mode gets its own command name`() {
    val s = session()
    s.run("inoremap jk <Esc>")
    s.run("vnoremap Y y$")
    s.run("mkvimrc")

    assertTrue("inoremap jk <Esc>" in s.written!!, "got ${s.written}")
    assertTrue("vnoremap Y y$" in s.written!!, "got ${s.written}")
  }

  /**
   * An option at its default is not something the user chose, and a config full of them hides the
   * ones that are - which is the rule `:set` with no arguments prints by.
   */
  @Test
  fun `test only the options that were changed are written`() {
    val s = session()
    s.run("set scrolloff=5")
    s.run("mkvimrc")

    assertTrue("set scrolloff=5" in s.written!!, "got ${s.written}")
    assertTrue("set scrolljump" !in s.written!!, "an untouched option should not be in the file")
  }

  @Test
  fun `test a toggle option is written as set or set no`() {
    val s = session()
    s.run("set number")
    s.run("set nowrapscan")
    s.run("mkvimrc")

    assertTrue("set number" in s.written!!, "got ${s.written}")
    assertTrue("set nowrapscan" in s.written!!, "got ${s.written}")
  }

  /**
   * The round trip is the whole point: what this writes has to be what this reads.
   *
   * Every line of the file is run back through the same executor a config goes through, and the
   * session that results has to write the same file again. That is a stronger check than comparing
   * strings - it says the commands are spelled the way the parser spells them, and it is the thing
   * that breaks when an option's value needs quoting or a key needs different notation.
   */
  @Test
  fun `test what it writes is what this engine reads`() {
    val first = session()
    first.run("nnoremap Q db")
    first.run("set scrolloff=7")
    first.run("mkvimrc")
    val config = first.written!!

    val second = session()
    second.messages.clearError()
    for (line in config.lines()) {
      if (line.isNotBlank() && !line.startsWith("\"")) second.run(line)
    }

    assertEquals(null, second.messages.lastError, "every line it wrote should be a command it accepts")

    second.run("mkvimrc /work/again")
    assertEquals(
      config,
      (injector.fileSystem as HeadlessFileSystem).written["/work/again"],
      "reading the file back should give a session that writes the same file",
    )
  }

  // Refusing.

  @Test
  fun `test a file that is already there is E189 and is not touched`() {
    val s = session()
    s.files.written["/work/.ideavimrc"] = "the user's own config"
    s.messages.clearError()
    s.run("mkvimrc")

    assertTrue(s.messages.lastError?.contains("E189") == true, "got ${s.messages.lastError}")
    assertEquals("the user's own config", s.written, "the file must be left exactly as it was")
  }

  @Test
  fun `test a bang overwrites it`() {
    val s = session()
    s.files.written["/work/.ideavimrc"] = "the user's own config"
    s.run("mkvimrc!")

    assertTrue(s.written!!.startsWith("\" Written by"), "got ${s.written}")
  }

  @Test
  fun `test a write that fails says so rather than reporting success`() {
    val s = session()
    s.files.writeFailure = "read-only file system"
    s.messages.clearError()
    s.run("mkvimrc")

    assertTrue(s.messages.lastError?.contains("E212") == true, "got ${s.messages.lastError}")
  }
}
