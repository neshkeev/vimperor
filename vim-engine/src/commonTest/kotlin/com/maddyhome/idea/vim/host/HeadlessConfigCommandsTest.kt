/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.mark.Jump
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:saveas`, `:oldfiles`, and the family of commands that name what the editor already decided.
 *
 * The last group is the one with an argument behind it. `:syntax on` is in the first twenty lines
 * of a great many `~/.vimrc` files, and an unknown ex command is `E492` - so a `~/.vimrc` sourced
 * from an `.ideavimrc` used to produce one line of red per line of config, which is a config that
 * stops being read rather than a config with a problem. These accept quietly, and speak up only
 * when the argument asks for something the editor will not do.
 *
 * They lived in the VS Code host until now, which is why the first test here is the one that runs
 * them all and asserts nothing was reported: it is the whole point of the group, and it is what the
 * IntelliJ plugin did not have.
 */
class HeadlessConfigCommandsTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret), path = "/work/a.txt")
      .also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    fun clearPanel() = injector.outputPanel.clear(editor, HeadlessExecutionContext)

    fun printed(command: String): String {
      clearPanel()
      run(command)
      return panel.lines.joinToString("")
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // ---- what a vimrc opens with ----------------------------------------------------------------------

  /**
   * The opening lines of a borrowed `~/.vimrc`, run one after another, reporting nothing.
   *
   * This is the test the whole group exists for. Each of these was `E492` in the IntelliJ plugin
   * until they moved into the engine, and a reader whose config produces eight errors before it
   * gets to the mappings stops reading the errors.
   */
  @Test
  fun `test the lines a borrowed vimrc opens with are all accepted`() {
    val s = session()
    for (line in listOf(
      "syntax on",
      "syntax enable",
      "filetype plugin indent on",
      "colorscheme",
      "runtime! plugin/sensible.vim",
      "packloadall",
      "helptags ALL",
      "mkview",
      "loadview",
    )) {
      s.messages.clearError()
      s.run(line)
      assertNull(s.messages.lastError, "`:$line` should not report anything")
    }
  }

  /** Accepting quietly is only half of it: a contradiction gets a word, because it is rare. */
  @Test
  fun `test syntax off says that it did nothing`() {
    val s = session()
    s.run("syntax off")

    assertTrue(s.messages.lastMessage?.contains("`:syntax off` did nothing") == true, "${s.messages.lastMessage}")
  }

  @Test
  fun `test filetype off says so and filetype on does not`() {
    val s = session()
    s.run("filetype plugin indent on")
    assertNull(s.messages.lastMessage)

    s.run("filetype off")
    assertTrue(s.messages.lastMessage?.contains("did nothing") == true, "${s.messages.lastMessage}")
  }

  /** Vim prints the current scheme for the bare form, so the bare form is not a contradiction. */
  @Test
  fun `test colorscheme with a name says so and without one stays quiet`() {
    val s = session()
    s.run("colorscheme")
    assertNull(s.messages.lastMessage)

    s.run("colorscheme desert")
    assertTrue(s.messages.lastMessage?.contains("`:colorscheme desert` did nothing") == true)
  }

  // ---- :version --------------------------------------------------------------------------------------

  /** Not one of the quiet ones: it asks a question that has an answer worth printing. */
  @Test
  fun `test version says which fork this is`() {
    val s = session()
    val printed = s.printed("version")

    assertTrue("Vimperor" in printed, printed)
    assertTrue("IdeaVim" in printed, printed)
  }

  /** Vim has ignored `:version {nr}` for twenty years. So does this. */
  @Test
  fun `test version with a number is still the version`() {
    val s = session()

    assertTrue("Vimperor" in s.printed("version 700"))
  }

  // ---- :saveas ---------------------------------------------------------------------------------------

  /**
   * The difference from `:write {file}`: you end up editing the new file.
   *
   * `:w other.txt` writes a copy and leaves you where you were; `:saveas other.txt` writes it and
   * moves you. Both halves are checked, because writing without opening is exactly `:write` and
   * would pass a test that only looked at the file.
   */
  @Test
  fun `test saveas writes the file and opens it`() {
    val s = session()
    s.run("saveas /work/copy.txt")

    assertEquals("one\ntwo", s.files.written["/work/copy.txt"])
    assertEquals(listOf("/work/copy.txt"), (injector.file as HeadlessFile).opened)
  }

  @Test
  fun `test saveas refuses to overwrite without a bang`() {
    val s = session()
    s.files.written["/work/copy.txt"] = "precious"
    s.run("saveas /work/copy.txt")

    assertEquals("""E13: File exists (add ! to override): /work/copy.txt""", s.messages.lastError)
    assertEquals("precious", s.files.written["/work/copy.txt"])
  }

  @Test
  fun `test the bang overwrites`() {
    val s = session()
    s.files.written["/work/copy.txt"] = "precious"
    s.run("saveas! /work/copy.txt")

    assertEquals("one\ntwo", s.files.written["/work/copy.txt"])
  }

  @Test
  fun `test saveas with no name is E471`() {
    val s = session()
    s.run("saveas")

    assertEquals("E471: Argument required", s.messages.lastError)
  }

  // ---- :oldfiles --------------------------------------------------------------------------------------

  /**
   * Newest first, and no repeats - which is what makes a list of jumps a list of *files*.
   *
   * A jump list holds a place per jump, so a file jumped around in appears many times; the command
   * is about files, so it keeps the first sighting of each walking backwards.
   */
  @Test
  fun `test oldfiles lists the jump list's files newest first`() {
    val s = session()
    val jumps = injector.jumpService
    jumps.addJump(s.editor.projectId, Jump(0, 0, "/work/first.kt", "file"), false)
    jumps.addJump(s.editor.projectId, Jump(3, 0, "/work/second.kt", "file"), false)
    jumps.addJump(s.editor.projectId, Jump(9, 0, "/work/first.kt", "file"), false)

    assertEquals("  1: /work/first.kt\n  2: /work/second.kt\n", s.printed("oldfiles"))
  }

  /** An empty list says why it is empty, since a reader expecting last week's files should know. */
  @Test
  fun `test oldfiles explains itself when there is nothing in it yet`() {
    val s = session()
    val printed = s.printed("oldfiles")

    assertTrue("no viminfo file" in printed, printed)
  }
}
