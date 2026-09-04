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
 * `:e file` and `:w file`, which name a file rather than acting on the one in the editor.
 *
 * Real files, in a real directory, because the whole question here is what happens on disk. The two
 * commands come from opposite directions and it is worth seeing that in the tests: `:w file` never
 * touches VS Code and is checked by reading the file back, `:e file` never touches the disk beyond
 * asking whether the file is there and is checked by what it asked VS Code to open.
 */
class VsCodeNamedFileTest {

  private class RecordingSink : MessageSink {
    val said: MutableList<String> = mutableListOf()
    override fun message(text: String?) { text?.let { said += it } }
    override fun error(text: String?) { text?.let { said += it } }
    override fun status(text: String?) {}
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val dispatched: MutableList<String> = mutableListOf()
    val opened: MutableList<String> = mutableListOf()
    val sink = RecordingSink()
    val host = VimHost(
      sink = sink,
      runCommand = { command, args, onDone ->
        dispatched += command
        args.firstOrNull()?.let { opened += (it.asDynamic().fsPath as String) }
        onDone(true)
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  @Test
  fun `test colon w with a name writes the buffer to that file`() {
    val directory = temporaryFileDirectory()
    val session = Session("one two\nthree four")

    session.type(":w $directory/out.txt")
    session.key("<CR>")

    assertEquals("one two\nthree four", readFile("$directory/out.txt"))
    assertEquals(emptyList(), session.dispatched, "writing a named file is not VS Code's business")
  }

  /** A range writes only those lines, which is what `:1w file` is for. */
  @Test
  fun `test a range on colon w writes only those lines`() {
    val directory = temporaryFileDirectory()
    val session = Session("one\ntwo\nthree")

    session.type(":2w $directory/second.txt")
    session.key("<CR>")

    assertEquals("two\n", readFile("$directory/second.txt"))
  }

  /**
   * Vim refuses to overwrite unless told twice. The engine does the refusing; this is here because
   * it can only refuse if the host can say whether the file is there, which is `findFile`.
   */
  @Test
  fun `test colon w will not overwrite an existing file`() {
    val directory = temporaryFileDirectory()
    writeFileAt("$directory/taken.txt", "do not lose me")
    val session = Session("new content")

    session.type(":w $directory/taken.txt")
    session.key("<CR>")

    assertEquals("do not lose me", readFile("$directory/taken.txt"))
    assertTrue(session.sink.said.any { it.contains("E37") }, "expected E37, said ${session.sink.said}")
  }

  @Test
  fun `test colon w bang overwrites it`() {
    val directory = temporaryFileDirectory()
    writeFileAt("$directory/taken.txt", "do not lose me")
    val session = Session("new content")

    session.type(":w! $directory/taken.txt")
    session.key("<CR>")

    assertEquals("new content", readFile("$directory/taken.txt"))
  }

  @Test
  fun `test colon e asks VS Code to open the file`() {
    val directory = temporaryFileDirectory()
    writeFileAt("$directory/other.txt", "somewhere else")
    val session = Session("one two")

    session.type(":e $directory/other.txt")
    session.key("<CR>")

    assertEquals(listOf("vscode.open"), session.dispatched)
    assertEquals(listOf("$directory/other.txt"), session.opened)
  }

  /**
   * `:e newfile` gives an empty buffer with that name, the way Vim does.
   *
   * This used to answer E447, and the comment explaining why said an untitled document "has no
   * path until it is saved". That is true of `newUntitledFile` and false of the `untitled:` scheme,
   * which takes one: `untitled:/dir/new.txt` is an unsaved buffer that `:w` writes to exactly that
   * file, with no dialog.
   */
  @Test
  fun `test colon e on a file that is not there opens a buffer with that name`() {
    val directory = temporaryFileDirectory()
    val session = Session("one two")

    session.type(":e $directory/missing.txt")
    session.key("<CR>")

    assertEquals(listOf("vscode.open"), session.dispatched)
    assertEquals(listOf("untitled:$directory/missing.txt"), session.opened)
    assertTrue(session.sink.said.none { it.contains("E447") }, "said ${session.sink.said}")
  }

  /**
   * A path is Vim's, so `$VAR` in it is expanded before anything looks for the file. Checked
   * through what was opened rather than through an error message, since an error message repeats
   * the path as it was typed and would say nothing about whether it was expanded.
   */
  @Test
  fun `test an environment variable in the path is expanded`() {
    val directory = temporaryFileDirectory()
    writeFileAt("$directory/variable.txt", "found by expansion")
    setEnvironment("IDEAVIM_TEST_DIRECTORY", directory)
    val session = Session("one two")

    session.type(":e ${'$'}IDEAVIM_TEST_DIRECTORY/variable.txt")
    session.key("<CR>")

    assertEquals(listOf("$directory/variable.txt"), session.opened)
  }

  /** And a relative one is resolved against the workspace, which these tests do not have. */
  @Test
  fun `test a relative path with no workspace open is left alone`() {
    val session = Session("one two")

    session.type(":e relative-to-nothing.txt")
    session.key("<CR>")

    assertEquals(listOf("untitled:relative-to-nothing.txt"), session.opened)
  }
}

private fun temporaryFileDirectory(): String = temporaryDirectory()

private fun writeFileAt(path: String, content: String) = writeFile(path, content)

private fun readFile(path: String): String = nodeFs.readFileSync(path, "utf8") as String

private fun setEnvironment(name: String, value: String) {
  js("process").env[name] = value
}
