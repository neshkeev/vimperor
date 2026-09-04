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
 * `:registers` and `:marks`, printed to an output channel.
 *
 * Vim's output panel takes over the screen and then takes keys - space pages, `q` closes, and
 * nothing else happens until it does. VS Code has no equivalent that does not fight the editor for
 * focus, so this prints and gets out of the way. The deliberate consequence is that typing carries
 * on working while the output is showing, which is what a VS Code user expects and is not what Vim
 * does.
 */
class OutputPanelTest {

  /** An output channel a test can read back. */
  private class RecordingChannel : OutputChannel {
    val lines: MutableList<String> = mutableListOf()
    var shown: Int = 0
      private set

    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {
      shown++
    }

    override fun dispose() {}
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val channel = RecordingChannel()
    val host = VimHost(outputPanel = OutputChannelPanelService(channel)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun run(command: String) {
      type(":")
      type(command)
      key("<CR>")
    }

    val printed: String get() = channel.lines.joinToString("\n")
  }

  @Test
  fun `test registers prints what was yanked`() {
    val session = Session("hello world")
    session.type("\"ayw")

    session.run("registers")

    assertTrue(session.printed.contains("hello"), "the yanked text should be listed, but got:\n${session.printed}")
    assertTrue(session.printed.contains("a"), "the register it went to should be named")
  }

  @Test
  fun `test marks prints a mark that was set`() {
    val session = Session("one\ntwo\nthree")
    session.type("jma")

    session.run("marks")

    assertTrue(session.printed.contains("a"), "the mark should be listed, but got:\n${session.printed}")
  }

  @Test
  fun `test the channel is revealed when there is output`() {
    val session = Session("text")
    session.run("registers")

    assertTrue(session.channel.shown > 0, "output nobody is shown is output nobody reads")
  }

  @Test
  fun `test typing carries on while output is showing`() {
    // Vim would be waiting for a key to dismiss the panel. This host has no panel to dismiss, so
    // the next keystroke is a Vim command - recorded because it is a difference, not an accident.
    val session = Session("one two")
    session.run("registers")

    session.type("dw")

    assertEquals("two", session.fake.document.content)
  }

  @Test
  fun `test a host with nowhere to print does not fail the command`() {
    // The default when no channel is supplied. Losing the text of an informational command is not
    // worth stopping a session for.
    val fake = FakeEditor("one two")
    val host = VimHost().also { it.start() }
    KeyHandler.getInstance().fullReset(host.editorFor(fake))

    host.type(fake, ":")
    "registers".forEach { host.type(fake, it.toString()) }
    host.key(fake, "<CR>")

    assertEquals("NORMAL", host.modeName(), "the command should have completed")
  }
}
