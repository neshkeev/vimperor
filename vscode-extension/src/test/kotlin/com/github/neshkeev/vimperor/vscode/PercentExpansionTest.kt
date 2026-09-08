/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.GlobalOptions
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimProcessGroupBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `%` on the command line: the current file name, with `:p`, `:h`, `:t`, `:r` and `:e`.
 *
 * `:!` is the sharp end of this and the reason there is a host test rather than only the engine's
 * own `PathCompletionArgumentParserTest`. What `%` becomes is the host's answer, and this host had
 * it wrong in a way no engine test could see: `:!` used to append `editor.getVirtualFile()?.path`,
 * which is not a path but the editor's *identity*, spelled `scheme://path` here - so `:!wc %`
 * handed the shell `file:///test/buffer.txt`. Every assertion below would have passed with a
 * leading `file://` if it only checked that the name was in there, so they check the whole string.
 *
 * There is no workspace folder in a test, so a buffer name is its absolute path - which is also
 * what Vim shows for a file outside the current directory.
 */
class PercentExpansionTest {

  private class FakeShell : VimProcessGroupBase() {
    var ranCommand: String? = null
      private set

    override fun executeCommand(
      editor: VimEditor,
      command: String,
      input: CharSequence?,
      currentDirectoryPath: String?,
      options: GlobalOptions,
    ): String {
      ranCommand = command
      return ""
    }
  }

  private class Session(path: String = "/work/src/notes.md") {
    val fake = FakeEditor("one two", path)
    val shell = FakeShell()
    val errors: MutableList<String> = mutableListOf()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      processes = shell,
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun run(line: String) {
      host.key(fake, "<Esc>")
      ":$line".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }
  }

  @Test
  fun `test percent is the current file name`() {
    val session = Session()
    session.run("!wc %")
    assertEquals("wc /work/src/notes.md", session.shell.ranCommand)
  }

  /** `:t` is the tail - the file name with every directory stripped. */
  @Test
  fun `test tail modifier`() {
    val session = Session()
    session.run("!wc %:t")
    assertEquals("wc notes.md", session.shell.ranCommand)
  }

  /** `:h` is the head - the directory holding the file. */
  @Test
  fun `test head modifier`() {
    val session = Session()
    session.run("!ls %:h")
    assertEquals("ls /work/src", session.shell.ranCommand)
  }

  /** `:r` drops the last extension, `:e` keeps only it. They chain with the rest. */
  @Test
  fun `test root and extension modifiers`() {
    val session = Session()
    session.run("!echo %:t:r %:e")
    assertEquals("echo notes md", session.shell.ranCommand)
  }

  /** A backslash removes the special meaning, and Vim drops the backslash with it. */
  @Test
  fun `test escaped percent is a literal percent`() {
    val session = Session()
    session.run("""!echo 50\%""")
    assertEquals("echo 50%", session.shell.ranCommand)
  }

  /** Text either side of the name survives, and an unknown modifier is literal text. */
  @Test
  fun `test surrounding text and an unknown modifier are left alone`() {
    val session = Session()
    session.run("!cat %:z | head")
    assertEquals("cat /work/src/notes.md:z | head", session.shell.ranCommand)
  }

  /** Vim's E499: a buffer with no name has nothing for `%` to become, and the command does not run. */
  @Test
  fun `test unnamed buffer reports E499 and runs nothing`() {
    val session = Session()
    val untitled = FakeEditor("", "Untitled-1", untitled = true)
    KeyHandler.getInstance().fullReset(session.host.editorFor(untitled))
    session.host.key(untitled, "<Esc>")
    ":!wc %".forEach { session.host.type(untitled, it.toString()) }
    session.host.key(untitled, "<CR>")

    assertEquals(null, session.shell.ranCommand)
    assertTrue(session.errors.any { it.contains("E499") }, "expected E499, got ${session.errors}")
  }
}
