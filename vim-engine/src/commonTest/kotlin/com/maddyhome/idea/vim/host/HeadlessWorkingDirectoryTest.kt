/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:cd`, `:lcd` and the current directory neither host has one of.
 *
 * The property worth pinning hardest is the one about *not* changing anything: a relative path is
 * handed to the host untouched until somebody runs `:cd`, so no existing config moves and the two
 * hosts keep resolving `:e foo` exactly as they did. That is the first test here and it is the one
 * that would break silently.
 *
 * After a `:cd`, the engine resolves. That is what makes `:cd` mean something at all, because both
 * hosts resolve a relative path behind their own boundaries - IntelliJ's goes over RPC into the
 * backend - and neither can be told about a directory the engine owns.
 */
class HeadlessWorkingDirectoryTest {

  private class Session(path: String = "/work/buffer.txt") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret), path = path).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem
    val opened: List<String> get() = (injector.file as HeadlessFile).opened

    /** A directory exists when something is written under it - see [HeadlessFileSystem]. */
    fun makeDirectory(path: String) {
      files.written["$path/.keep"] = ""
    }

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

  private fun session(path: String = "/work/buffer.txt"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(path)
  }

  /**
   * The whole reason this can be added safely: until a `:cd` runs, the host still gets what the
   * user typed and resolves it as it always has.
   */
  @Test
  fun `test a relative path is left to the host until a cd has run`() {
    val s = session()
    s.run("e notes.txt")

    assertEquals(listOf("notes.txt"), s.opened, "the host must still see the name it always saw")
  }

  @Test
  fun `test after a cd the engine resolves the path itself`() {
    val s = session()
    s.makeDirectory("/elsewhere")
    s.run("cd /elsewhere")
    s.run("e notes.txt")

    assertEquals(listOf("/elsewhere/notes.txt"), s.opened)
  }

  @Test
  fun `test an absolute path is never touched`() {
    val s = session()
    s.makeDirectory("/elsewhere")
    s.run("cd /elsewhere")
    s.run("e /other/notes.txt")

    assertEquals(listOf("/other/notes.txt"), s.opened)
  }

  // `:pwd`

  @Test
  fun `test pwd reports the host root until a cd, then the directory`() {
    val s = session()
    s.run("pwd")
    assertEquals("/work", s.messages.lastMessage, "the host's root, which is what it printed before")

    s.makeDirectory("/elsewhere")
    s.run("cd /elsewhere")
    s.run("pwd")
    assertEquals("/elsewhere", s.messages.lastMessage)
  }

  // Refusing.

  @Test
  fun `test a directory that is not there is refused and changes nothing`() {
    val s = session()
    s.messages.clearError()
    s.run("cd /nowhere")

    assertTrue(s.messages.lastError?.contains("E344") == true, "got ${s.messages.lastError}")
    s.run("e notes.txt")
    assertEquals(listOf("notes.txt"), s.opened, "a refused :cd must leave resolution alone")
  }

  /** A file is not a directory, and resolving into one would break every later path. */
  @Test
  fun `test a file is not accepted as a directory`() {
    val s = session()
    s.files.written["/work/notes.txt"] = "text"
    s.messages.clearError()
    s.run("cd /work/notes.txt")

    assertTrue(s.messages.lastError?.contains("E344") == true, "got ${s.messages.lastError}")
  }

  // The forms people type without thinking.

  @Test
  fun `test cd minus goes back to where you were`() {
    val s = session()
    s.makeDirectory("/one")
    s.makeDirectory("/two")
    s.run("cd /one")
    s.run("cd /two")
    s.run("cd -")
    s.run("pwd")

    assertEquals("/one", s.messages.lastMessage)
  }

  @Test
  fun `test cd minus with nowhere to go back to is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("cd -")

    assertTrue(s.messages.lastError != null, "there is no previous directory yet")
  }

  @Test
  fun `test chdir is the same command`() {
    val s = session()
    s.makeDirectory("/elsewhere")
    s.run("chdir /elsewhere")
    s.run("pwd")

    assertEquals("/elsewhere", s.messages.lastMessage)
  }

  // Window-local.

  @Test
  fun `test lcd sets this window's directory and leaves the global one`() {
    val s = session()
    s.makeDirectory("/global")
    s.makeDirectory("/local")
    s.run("cd /global")
    s.run("lcd /local")
    s.run("pwd")
    assertEquals("/local", s.messages.lastMessage)

    // Another window, which has no local directory and so follows the global one.
    val second = TestVimCaret(0, isPrimary = true)
    val other = TestVimEditor("x", listOf(second), path = "/work/other.txt").also { second.editorRef = it }
    assertEquals("/global", WorkingDirectory.currentOrNull(other))
  }

  /** Vim's `:cd` clears the window-local directory, so the window follows the global one again. */
  @Test
  fun `test a later cd takes the window's own directory away`() {
    val s = session()
    s.makeDirectory("/local")
    s.makeDirectory("/global")
    s.run("lcd /local")
    s.run("cd /global")
    s.run("pwd")

    assertEquals("/global", s.messages.lastMessage)
  }

  /** `:tcd` is Vim's tab-local form, and a tab here holds one editor. */
  @Test
  fun `test tcd is lcd in a host whose tab is a window`() {
    val s = session()
    s.makeDirectory("/local")
    s.run("tcd /local")

    assertEquals("/local", WorkingDirectory.currentOrNull(s.editor))
  }

  // The commands written earlier that resolve paths follow it too.

  @Test
  fun `test vimgrep searches relative to the current directory`() {
    val s = session()
    s.files.written["/elsewhere/a.kt"] = "needle\n"
    s.run("cd /elsewhere")
    s.run("""vimgrep /needle/j a.kt""")
    s.run("clist")

    assertTrue(
      "a.kt" in (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n"),
      "the glob should have resolved against the current directory",
    )
  }

  @Test
  fun `test mkvimrc writes relative to the current directory`() {
    val s = session()
    s.makeDirectory("/elsewhere")
    s.run("cd /elsewhere")
    s.run("mkvimrc")

    assertTrue("/elsewhere/.ideavimrc" in s.files.written.keys, "wrote ${s.files.written.keys}")
  }
}
