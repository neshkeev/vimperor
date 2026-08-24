/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user's `.ideavimrc`, which is how anyone actually configures Vim.
 *
 * Read through Node's `fs` rather than VS Code's `workspace.fs`, for the same reason the command
 * line does not use `showInputBox`: `readFileSync` returns the contents and `workspace.fs` returns
 * a promise, and `:source` has to return with the file. That holds for local workspaces and for
 * SSH and dev containers, where the extension host runs on the remote machine and reads the
 * `.ideavimrc` that is actually there. A web-only workspace has no Node and will need an
 * asynchronous load at startup.
 */
class VimRcTest {

  /** What Vim said, so a config that failed to run says why instead of failing silently. */
  private class RecordingSink : MessageSink {
    val errors: MutableList<String> = mutableListOf()
    override fun message(text: String?) {}
    override fun error(text: String?) { text?.let { errors += it } }
    override fun status(text: String?) {}
  }

  private class Session(text: String, private val files: Map<String, String>) {
    val fake = FakeEditor(text)
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun load(environment: Map<String, String>): String? =
      host.loadVimRc(host.editorFor(fake)) { environment[it] }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  /** Writes the files a test needs, so the config really is read off a disk. */
  private fun writing(files: Map<String, String>): String {
    val directory = temporaryDirectory()
    files.forEach { (name, content) -> writeFile("$directory/$name", content) }
    return directory
  }

  @Test
  fun `test an option set in the config takes effect`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n"))
    val session = Session("Foo foo", emptyMap())

    val loaded = session.load(mapOf("HOME" to home))

    assertEquals(emptyList(), session.sink.errors, "the config should have run without complaint")
    assertEquals("$home/.ideavimrc", loaded)
    assertTrue(
      injector.globalOptions().ignorecase,
      "the option should be set the way the config asked",
    )
  }

  @Test
  fun `test a mapping from the config changes what a key does`() {
    // The thing a user notices. Without the config, `Q` is a Vim command of its own.
    val home = writing(mapOf(".ideavimrc" to "nnoremap Q dw\n"))
    val session = Session("one two", emptyMap())
    session.load(mapOf("HOME" to home))

    session.type("Q")

    assertEquals("two", session.content, "Q should have been remapped to dw")
  }

  @Test
  fun `test _ideavimrc is found when the dotted name is not there`() {
    val home = writing(mapOf("_ideavimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/_ideavimrc", session.load(mapOf("HOME" to home)))
  }

  @Test
  fun `test the XDG location is searched after the home directory`() {
    val home = writing(mapOf("ideavim/ideavimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals(
      "$home/ideavim/ideavimrc",
      session.load(mapOf("HOME" to "/nowhere", "XDG_CONFIG_HOME" to home)),
    )
  }

  @Test
  fun `test IDEA_VIM_CUSTOM_VIMRC wins over everything`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n", "elsewhere.vim" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals(
      "$home/elsewhere.vim",
      session.load(mapOf("HOME" to home, "IDEA_VIM_CUSTOM_VIMRC" to "$home/elsewhere.vim")),
    )
  }

  @Test
  fun `test no config is not an error`() {
    val session = Session("text", emptyMap())
    assertNull(session.load(mapOf("HOME" to "/nowhere-at-all")))
  }

  @Test
  fun `test a broken line does not stop the rest of the config`() {
    // Vim carries on after an error in the vimrc, and a user with one typo still wants the other
    // twenty lines. An extension that refused to start over a bad line would be worse than Vim.
    val home = writing(mapOf(".ideavimrc" to "this is not a command\nnnoremap Q dw\n"))
    val session = Session("one two", emptyMap())
    session.load(mapOf("HOME" to home))

    session.type("Q")

    assertEquals("two", session.content, "the mapping after the broken line should still be set")
  }
}

private val nodeFs: dynamic = requireModule("fs")
private val nodeOs: dynamic = requireModule("os")
private val nodePath: dynamic = requireModule("path")

private fun requireModule(module: String): dynamic = js("require")(module)

private var directoryCount = 0

private fun temporaryDirectory(): String {
  // Named by a counter rather than at random: workflow scripts aside, a test that picks a fresh
  // name each run leaves a trail of directories behind it.
  val base = nodePath.join(nodeOs.tmpdir(), "ideavim-vimrc-test-${directoryCount++}") as String
  nodeFs.rmSync(base, js("({recursive: true, force: true})"))
  nodeFs.mkdirSync(base, js("({recursive: true})"))
  return base
}

private fun writeFile(path: String, content: String) {
  nodeFs.mkdirSync(nodePath.dirname(path), js("({recursive: true})"))
  nodeFs.writeFileSync(path, content)
}
