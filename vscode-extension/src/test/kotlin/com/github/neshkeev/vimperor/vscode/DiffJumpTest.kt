/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.getJumps
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.mark.DiffSide
import com.maddyhome.idea.vim.mark.JumpSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `<C-O>` back into the diff the jump was made in.
 *
 * A diff tab is recognised by the paths of its two sides, never by its label. A side that is not
 * the file exists only in that tab: once the tab is gone the jump is dropped, and the plain file
 * is not opened at the old revision's line.
 */
class DiffJumpTest {

  /** The stub's tabs are module state, so a test that changed them puts them back. */
  @AfterTest
  fun restoreTabs() {
    openTabs("/one")
    callsRaw().length = 0
    shownPathsRaw().length = 0
  }

  @Test
  fun `test a caret in the modified side records the diff and control-O shows that side`() {
    val session = Session("alpha\nbeta\ngamma line\ndelta")
    // A plain tab whose name is the file's, and a diff whose title is not. The match is the URIs.
    openTabs("/other/changed.txt")
    openDiff("Working Tree", "git", "/test/changed.txt", "file", "/test/changed.txt")
    val editor = session.editor
    place(editor, 2, 3)
    injector.jumpService.saveJumpLocation(editor)

    val jump = injector.jumpService.getJumps(editor).single()
    val source = jump.source
    assertIs<JumpSource.Diff>(source)
    assertEquals(DiffSide.RIGHT, source.side)
    assertTrue(source.isInFile)
    assertTrue(jump.isInFile)
    assertEquals("/test/changed.txt\u0000/test/changed.txt", source.diffId)
    assertEquals(2, jump.line)
    assertEquals(3, jump.col)

    place(editor, 0, 0)
    shownPathsRaw().length = 0
    callsRaw().length = 0
    session.key("<C-O>")

    assertEquals(2, lineOf(editor))
    assertEquals(3, columnOf(editor))
    assertEquals(emptyList(), shownPaths(), "a plain tab was not opened: ${shownPaths()}")
    val reveal = commandCalls().single { it.command == VsCodeCommands.DIFF }
    assertEquals(
      listOf("git:///test/changed.txt", "file:///test/changed.txt", "Working Tree"),
      reveal.args,
    )
  }

  @Test
  fun `test an original side that is not a file is shown while its diff is open`() {
    val session = Session("alpha\nbeta\ngamma\ndelta\nepsilon", scheme = "git")
    openDiff("revision", "git", "/test/changed.txt", "file", "/test/changed.txt")
    val editor = session.editor
    place(editor, 4, 1)
    injector.jumpService.saveJumpLocation(editor)

    val source = injector.jumpService.getJumps(editor).single().source
    assertIs<JumpSource.Diff>(source)
    assertEquals(DiffSide.LEFT, source.side)
    assertFalse(source.isInFile)

    place(editor, 0, 0)
    shownPathsRaw().length = 0
    callsRaw().length = 0
    session.key("<C-O>")

    assertEquals(4, lineOf(editor))
    assertEquals(1, columnOf(editor))
    assertEquals(emptyList(), shownPaths(), "the plain file was not opened: ${shownPaths()}")
    assertEquals(VsCodeCommands.DIFF, commandCalls().single { it.command == VsCodeCommands.DIFF }.command)
  }

  @Test
  fun `test a closed non-file jump is dropped and the following file jump runs`() {
    val session = Session("alpha\nbeta\ngamma\ndelta\nepsilon", scheme = "git")
    val file = FakeEditor("one\ntwo\nthree", path = "/test/other.txt")
    val fileEditor = session.host.editorFor(file)
    place(fileEditor, 1, 2)
    injector.jumpService.saveJumpLocation(fileEditor)

    openDiff("revision", "git", "/test/changed.txt", "file", "/test/changed.txt")
    val git = session.editor
    place(git, 4, 1)
    injector.jumpService.saveJumpLocation(git)
    assertFalse(injector.jumpService.getJumps(git).last().isInFile)

    closeDiffs()
    place(git, 0, 0)
    shownPathsRaw().length = 0
    callsRaw().length = 0
    session.key("<C-O>")

    assertEquals(0, lineOf(git), "the closed revision is not where the caret goes")
    assertEquals(1, lineOf(fileEditor))
    assertEquals(2, columnOf(fileEditor))
    assertTrue(injector.jumpService.getJumps(git).none { it.source is JumpSource.Diff })
    assertEquals(emptyList(), shownPaths(), "the plain file was not opened: ${shownPaths()}")
    assertTrue(commandCalls().none { it.command == VsCodeCommands.DIFF }, "a closed diff is not reopened")
  }

  @Test
  fun `test a diff labelled with the file name but different paths is not that file`() {
    val session = Session("alpha\nbeta")
    openDiff("changed.txt", "file", "/elsewhere/left.txt", "file", "/elsewhere/right.txt")
    val editor = session.editor
    injector.jumpService.saveJumpLocation(editor)

    assertEquals(JumpSource.File, injector.jumpService.getJumps(editor).single().source)
  }

  private class Session(
    text: String,
    path: String = "/test/changed.txt",
    scheme: String = "file",
  ) {
    val fake = FakeEditor(text, path = path, scheme = scheme)
    val host = VimHost().also { it.start() }
    val editor: VsCodeEditor

    init {
      editor = host.editorFor(fake)
      KeyHandler.getInstance().fullReset(editor)
    }

    fun key(notation: String) = host.key(fake, notation)
  }
}

private fun openTabs(vararg paths: String) {
  window.tabGroups.asDynamic()._openFiles(paths)
}

private fun openDiff(
  label: String,
  originalScheme: String,
  originalPath: String,
  modifiedScheme: String,
  modifiedPath: String,
) {
  window.tabGroups.asDynamic()._openDiff(label, originalScheme, originalPath, modifiedScheme, modifiedPath)
}

private fun closeDiffs() {
  window.tabGroups.asDynamic()._closeDiffs()
}

private fun place(editor: VsCodeEditor, line: Int, column: Int) {
  val offset = editor.bufferPositionToOffset(BufferPosition(line, column, false))
  editor.currentCaret().moveToOffset(offset)
}

private fun lineOf(editor: VsCodeEditor): Int = editor.primaryCaret().getBufferPosition().line

private fun columnOf(editor: VsCodeEditor): Int = editor.primaryCaret().getBufferPosition().column

private data class CommandCall(val command: String, val args: List<String>)

private fun callsRaw(): dynamic = js("require('vscode')").commands.calls

private fun commandCalls(): List<CommandCall> {
  val raw = callsRaw()
  return (0 until (raw.length as Int)).map { index ->
    val call = raw[index]
    val args = call.args
    CommandCall(
      call.command as String,
      (0 until (args.length as Int)).map { args[it] as String },
    )
  }
}

/** What `showTextDocument` was asked to reveal. Copied from [QuickfixWindowTest]; the stub is one list. */
private fun shownPathsRaw(): dynamic = js("require('vscode')").window.shownPaths

private fun shownPaths(): List<String> {
  val raw = shownPathsRaw()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
