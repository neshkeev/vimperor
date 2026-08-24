/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `:` and `/` prompts, typed rather than shown in a dialog.
 *
 * Worth stating what this is not. A Vim command line is not a box that collects a string and hands
 * it back - it is a text buffer the engine owns, keystroke by keystroke, with its own caret and
 * history, and `KeyHandler` routes keys into it while `CMD_LINE` mode is active. That is why the
 * asynchronous `showInputBox` never came into it: the host supplies a string and somewhere to draw
 * it, and both are synchronous.
 */
class VsCodeCommandLineTest {

  /** What a status bar would be showing, so a test can read the prompt as a user would see it. */
  private class RecordingDisplay : CommandLineDisplay {
    var shown: String? = null
      private set

    override fun show(text: String) {
      shown = text
    }

    override fun hide() {
      shown = null
    }
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val display = RecordingDisplay()
    val host = VimHost(commandLineDisplay = display).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
    val caretOffset: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  @Test
  fun `test colon opens a prompt`() {
    val session = Session("one two")
    session.type(":")

    assertEquals("COMMAND", session.host.modeName())
    assertEquals(":", session.display.shown, "the prompt should be showing its label")
  }

  @Test
  fun `test typing appears in the prompt rather than the buffer`() {
    val session = Session("one two")
    session.type(":")
    session.type("set")

    assertEquals(":set", session.display.shown)
    assertEquals("one two", session.content, "the buffer must not receive command line keys")
  }

  @Test
  fun `test Escape abandons the command`() {
    val session = Session("one two")
    session.type(":")
    session.type("junk")
    session.key("<Esc>")

    assertEquals("NORMAL", session.host.modeName())
    assertNull(session.display.shown, "the prompt should be gone")
    assertEquals("one two", session.content)
  }

  @Test
  fun `test a substitution typed at the prompt changes the buffer`() {
    // The whole point, and the first command a user runs that is not a keystroke.
    val session = Session("one two one")
    session.type(":")
    session.type("s/one/ONE/g")
    session.key("<CR>")

    assertEquals("ONE two ONE", session.content)
    assertEquals("NORMAL", session.host.modeName())
    assertNull(session.display.shown)
  }

  @Test
  fun `test a range applies to the lines it names`() {
    val session = Session("a\na\na")
    session.type(":")
    session.type("2s/a/b/")
    session.key("<CR>")

    assertEquals("a\nb\na", session.content)
  }

  @Test
  fun `test backspace removes the last character typed`() {
    val session = Session("one")
    session.type(":")
    session.type("sx")
    session.key("<BS>")

    assertEquals(":s", session.display.shown)
  }

  @Test
  fun `test a slash opens a search prompt with its own label`() {
    val session = Session("one two")
    session.type("/")

    assertEquals("COMMAND", session.host.modeName())
    assertEquals("/", session.display.shown, "search should be labelled the way Vim labels it")
  }

  @Test
  fun `test searching moves the caret to the match`() {
    val session = Session("one two three")
    session.type("/")
    session.type("three")
    session.key("<CR>")

    assertEquals(8, session.caretOffset, "the caret should be on the match")
  }

  @Test
  fun `test the command history remembers what was run`() {
    val session = Session("one")
    session.type(":")
    session.type("s/one/two/")
    session.key("<CR>")

    session.type(":")
    session.key("<Up>")

    assertTrue(
      session.display.shown?.contains("s/one/two/") == true,
      "the previous command should come back, but the prompt showed ${session.display.shown}",
    )
  }
}