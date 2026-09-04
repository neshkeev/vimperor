/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimFile
import com.maddyhome.idea.vim.api.injector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:ls`, `:buffers`, `:files` and `:buffer` - Vim's buffer list, over VS Code's tabs.
 *
 * These four were on the "only IdeaVim has them" list with the reason that VS Code's tab model does
 * not carry the state Vim prints. It does: `Tab.isDirty` is `+` and `Tab.isActive` is `%`. What the
 * host cannot answer is the read-only flag, which VS Code has no equivalent of, and the alternate
 * file - VS Code will *go* to it and will not say which it is - so those two columns are never
 * filled here, and the tests below say so rather than leaving it to be discovered.
 *
 * The exact spacing is asserted because it is the whole of what Vim specifies for this command, and
 * because it is what IdeaVim's own `BufferListCommandTest` asserts against the same engine code.
 */
class BufferListTest {

  /** The stub's tabs are module state, so a test that changed them puts them back. */
  @AfterTest
  fun restoreTabs() {
    openTabs("/one")
  }

  private fun openTabs(vararg paths: String): dynamic =
    window.tabGroups.asDynamic()._openFiles(paths)

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val channel = OutputRecorder()
    val host = VimHost(outputPanel = OutputChannelPanelService(channel)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun run(command: String) {
      host.type(fake, ":")
      command.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    val printed: String get() = channel.lines.joinToString("\n")
  }

  private class OutputRecorder : OutputChannel {
    val lines: MutableList<String> = mutableListOf()
    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {}
    override fun dispose() {}
  }

  /**
   * The row for the file the caret is in, spacing included.
   *
   * `%a` for the current buffer, the name padded to thirty columns, and `line: 1` because the
   * editor is registered and its caret is on the first line. The same shape IdeaVim prints, which
   * is now the same code printing it.
   */
  @Test
  fun `test ls prints the open file as the current buffer`() {
    openTabs("/test/buffer.txt")
    val session = Session("hello")

    session.run("ls")

    assertEquals("   1 %a   \"/test/buffer.txt\"             line: 1", session.printed)
  }

  /** `:files` and `:buffers` are the same command under two more names, as Vim has them. */
  @Test
  fun `test files and buffers print the same table`() {
    openTabs("/test/buffer.txt")
    val ls = Session("hello").also { it.run("ls") }.printed
    assertEquals(ls, Session("hello").also { it.run("files") }.printed)
    assertEquals(ls, Session("hello").also { it.run("buffers") }.printed)
  }

  /**
   * A tab with no editor behind it is listed at line 0.
   *
   * Which is what Vim prints for a buffer it has a name for and has not loaded, and is exactly the
   * state of a VS Code tab the user has not visited this session. Getting this from "the host has
   * never registered an editor for it" rather than inventing a line number is the point.
   */
  @Test
  fun `test a tab with no editor is listed as unloaded`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session("hello")

    session.run("ls")

    val rows = session.printed.split("\n")
    assertEquals(2, rows.size, "both tabs should be listed, but got:\n${session.printed}")
    assertTrue(rows[0].startsWith("   1 %a  "), "the open file should be the current buffer: ${rows[0]}")
    assertTrue(rows[1].startsWith("   2     "), "an unvisited tab has no flags: ${rows[1]}")
    assertTrue(rows[1].endsWith("line: 0"), "an unvisited tab has no cursor line: ${rows[1]}")
  }

  /** VS Code's dirty dot is Vim's `+`, which is the flag this table is usually read for. */
  @Test
  fun `test a modified tab is marked with a plus`() {
    val tabs = openTabs("/test/buffer.txt", "/test/other.txt")
    tabs[1].isDirty = true
    val session = Session("hello")

    session.run("ls")

    val rows = session.printed.split("\n")
    assertContains(rows[1], " + \"/test/other.txt\"")
    assertTrue(" + " !in rows[0], "the unmodified buffer should not be marked: ${rows[0]}")
  }

  /** The filters Vim takes after `:buffers`, which select rows by the flags they carry. */
  @Test
  fun `test the plus filter lists only modified buffers`() {
    val tabs = openTabs("/test/buffer.txt", "/test/other.txt")
    tabs[1].isDirty = true
    val session = Session("hello")

    session.run("buffers +")

    assertEquals(1, session.printed.split("\n").size, "only the modified buffer should be listed")
    assertContains(session.printed, "/test/other.txt")
  }

  /**
   * A filter Vim does not know is dropped rather than refused, which is what IdeaVim has always
   * done - `:buffers x` lists everything.
   */
  @Test
  fun `test an unsupported filter is ignored`() {
    openTabs("/test/buffer.txt")
    val session = Session("hello")

    session.run("buffers x")

    assertContains(session.printed, "/test/buffer.txt")
  }

  /** Tabs are numbered across every group, because that is the order the user sees them in. */
  @Test
  fun `test buffers in a second editor group are listed too`() {
    window.tabGroups.asDynamic()._openInTwoGroups(arrayOf("/test/buffer.txt"), arrayOf("/test/split.txt"))
    val session = Session("hello")

    session.run("ls")

    val rows = session.printed.split("\n")
    assertEquals(2, rows.size, "a tab in another group is still a buffer:\n${session.printed}")
    assertContains(rows[1], "/test/split.txt")
    assertTrue("%a" !in rows[1], "only the tab in the active group is current: ${rows[1]}")
  }

  /**
   * The path is shortened against the open folder, the way IdeaVim shortens against a project.
   *
   * The stub has no workspace folder by default - which is a real state, a loose file with no
   * folder open - so this one supplies its own host to check the shortening happens at all.
   */
  @Test
  fun `test the path is relative to the workspace folder`() {
    openTabs("/test/buffer.txt")
    val file = VsCodeFile(RecordingRunner(), workspaceRoot = { "/test" })

    val buffers = file.getBuffers(VsCodeExecutionContext)

    assertEquals("buffer.txt", buffers[0].displayPath)
  }

  /**
   * `:buffer N` opens by URI rather than by tab index.
   *
   * The two commands have to agree: `:ls` numbers across groups and past nine, and
   * `workbench.action.openEditorAtIndexN` - which this used to send - does neither.
   */
  @Test
  fun `test buffer by number opens the file that ls numbered`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val runner = RecordingRunner()
    val file = VsCodeFile(runner)

    assertTrue(file.selectFile(1, VsCodeExecutionContext), ":buffer 2 should have found a buffer")

    assertEquals(listOf(VsCodeCommands.OPEN), runner.commands)
    assertEquals("/test/other.txt", (runner.arguments[0][0] as Uri).path)
  }

  /** Past the end of the list there is no buffer, which is what `E86` is reported from. */
  @Test
  fun `test buffer by a number nobody has reports no such buffer`() {
    openTabs("/test/buffer.txt")
    val file = VsCodeFile(RecordingRunner())

    assertEquals(false, file.selectFile(8, VsCodeExecutionContext))
  }

  /**
   * `:bfirst` and `:blast` are the ends of the same list `:ls` numbers.
   *
   * Vim has four commands here over two lists - `:first`/`:last` walk the argument list and
   * `:bfirst`/`:blast` the buffer list - and this host has one list, so it has two commands under
   * four names. The pair that was missing is the one a config is more likely to write.
   */
  @Test
  fun `test bfirst opens the first buffer and blast the last`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val runner = RecordingRunner()
    val file = VsCodeFile(runner)

    assertTrue(file.selectFile(0, VsCodeExecutionContext), ":bfirst should have found a buffer")
    assertEquals("/test/buffer.txt", (runner.arguments[0][0] as Uri).path)

    assertTrue(file.selectFile(VimFile.LAST_FILE_SENTINEL, VsCodeExecutionContext), ":blast should have found one")
    assertEquals("/test/other.txt", (runner.arguments[1][0] as Uri).path)
  }

  /** ...and both spellings resolve to a command rather than to `E492`. */
  @Test
  fun `test bfirst and blast are commands this host knows`() {
    openTabs("/test/buffer.txt")
    val session = Session("hello")

    for (command in listOf("bfirst", "brewind", "blast")) {
      injector.messages.clearError()
      session.run(command)
      assertTrue(!injector.messages.isError(), "`:$command` should have resolved")
    }
  }

  /** `:buffer name` matches on the file's own name, not on the path `:ls` displays. */
  @Test
  fun `test buffer by name finds the one file it matches`() {
    openTabs("/test/buffer.txt", "/test/other.txt")
    val session = Session("hello")

    session.run("buffer other")

    assertTrue(!injector.messages.isError(), "a matched buffer should not have been an error")
  }

  /** Two matches is E93, which Vim reports rather than picking one. */
  @Test
  fun `test buffer by an ambiguous name is an error`() {
    openTabs("/test/report.txt", "/test/report2.txt")
    val session = Session("hello")

    session.run("buffer report")

    assertTrue(injector.messages.isError(), "an ambiguous `:buffer` should have been an error")
  }

  private class RecordingRunner : HostCommandRunner {
    val commands: MutableList<String> = mutableListOf()
    val arguments: MutableList<Array<Any?>> = mutableListOf()
    override fun run(command: String, arguments: Array<Any?>, waitForIt: Boolean, afterwards: () -> Unit) {
      commands += command
      this.arguments += arguments
    }
  }
}
