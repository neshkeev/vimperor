/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `gf` - open the file named under the caret.
 *
 * Real files in a real directory, because every question here is whether a name on a line resolves
 * to something on disk. The engine decides what the name is and where to look; the host answers
 * "is it there" and does the opening, so the assertions are about what VS Code was asked to open.
 *
 * IdeaVim has no `gf`, so there is no replayed fixture for any of this and no reference behaviour
 * to compare against beyond `:h gf`.
 */
class GotoFileTest {

  private class RecordingSink : MessageSink {
    val said: MutableList<String> = mutableListOf()
    override fun message(text: String?) { text?.let { said += it } }
    override fun error(text: String?) { text?.let { said += it } }
    override fun status(text: String?) { text?.let { said += it } }
  }

  private class Session(text: String, path: String) {
    val fake = FakeEditor(text, path)
    val opened: MutableList<String> = mutableListOf()
    val sink = RecordingSink()
    val host = VimHost(
      sink = sink,
      runCommand = { command, args, onDone ->
        if (command == "vscode.open") args.firstOrNull()?.let { opened += (it.asDynamic().fsPath as String) }
        onDone(true)
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  /** Vim's `'path'` starts with `.`, the directory of the current file. */
  @Test
  fun `test gf opens a file that sits beside the current one`() {
    val directory = temporaryDirectory()
    writeFile("$directory/notes.md", "the target")
    val session = Session("see notes.md for more", "$directory/spec.md")

    session.key("<Esc>")
    session.type("w")
    session.type("gf")

    assertEquals(listOf("$directory/notes.md"), session.opened, "said ${session.sink.said}")
  }

  /**
   * The caret does not have to be on the start of the name: Vim scans out to both ends of the
   * `'isfname'` run. This is the ordinary case, since you land mid-word.
   */
  @Test
  fun `test gf works with the caret in the middle of the name`() {
    val directory = temporaryDirectory()
    writeFile("$directory/notes.md", "the target")
    val session = Session("notes.md", "$directory/spec.md")

    session.key("<Esc>")
    session.type("3l")
    session.type("gf")

    assertEquals(listOf("$directory/notes.md"), session.opened, "said ${session.sink.said}")
  }

  /**
   * The case this was written for, end to end: a repository-root-relative path written in backticks
   * in a Markdown spec, several directories down from the root.
   *
   * Two things have to hold and both were once broken. `'isfname'` has no backtick in it, so the
   * name ends at the closing one rather than swallowing it and the `)` after - which is the whole
   * reason `gf` is usable in prose. And the second entry in Vim's default `'path'` is the current
   * directory, which is what resolves a path written from the root of a repository rather than
   * from the file it appears in.
   */
  @Test
  fun `test gf follows a backquoted repository-relative path out of a spec`() {
    val directory = temporaryDirectory()
    val target = "$directory/.omc/research/platform/memory-management-survey.md"
    writeFile(target, "# Memory management survey")
    val line = "(see `.omc/research/platform/memory-management-survey.md`): DuckDB's buffer pool"
    val session = Session(line, "$directory/docs/superpowers/specs/design.md")

    session.type(":cd $directory")
    session.key("<CR>")
    session.key("<Esc>")
    session.type("f.")
    session.type("gf")

    assertEquals(listOf(target), session.opened, "said ${session.sink.said}")
  }

  /** An absolute name needs no searching, and must not be joined onto anything. */
  @Test
  fun `test gf opens an absolute path`() {
    val directory = temporaryDirectory()
    writeFile("$directory/target.txt", "there")
    val session = Session("look at $directory/target.txt now", "/elsewhere/other.md")

    session.key("<Esc>")
    session.type("f/")
    session.type("gf")

    assertEquals(listOf("$directory/target.txt"), session.opened, "said ${session.sink.said}")
  }

  /**
   * Vim opens nothing and says E447 rather than creating the file - which is the difference between
   * `gf` and `:e`, and worth a test because this host's `openFile` *does* create a named empty
   * buffer for `:e`.
   */
  @Test
  fun `test gf on a name that is not there opens nothing and reports E447`() {
    val directory = temporaryDirectory()
    val session = Session("see missing.md for more", "$directory/spec.md")

    session.key("<Esc>")
    session.type("gf")

    assertEquals(emptyList(), session.opened)
    assertTrue(session.sink.said.any { it.contains("E447") }, "expected E447, said ${session.sink.said}")
  }

  @Test
  fun `test gf with no file name under the caret reports E446`() {
    val directory = temporaryDirectory()
    val session = Session("   ", "$directory/spec.md")

    session.key("<Esc>")
    session.type("gf")

    assertEquals(emptyList(), session.opened)
    assertTrue(session.sink.said.any { it.contains("E446") }, "expected E446, said ${session.sink.said}")
  }

  /**
   * In Visual mode the selection is the name as typed, with no `'isfname'` scan - selecting it is
   * how you say where it ends, which is the way to reach a name the option set would cut short.
   */
  @Test
  fun `test gf in Visual mode uses the selection`() {
    val directory = temporaryDirectory()
    writeFile("$directory/odd name.md", "the target")
    val session = Session("odd name.md", "$directory/spec.md")

    session.key("<Esc>")
    session.type("v\$")
    session.type("gf")

    assertEquals(listOf("$directory/odd name.md"), session.opened, "said ${session.sink.said}")
  }
}
