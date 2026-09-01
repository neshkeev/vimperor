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

/**
 * Matching a Vim regex against a plain string - `=~`, `!~`, `split()`, `:catch /pattern/`.
 *
 * This was listed as a service the host does not provide, and the service is `VimRegexServiceBase`:
 * the engine's own class over the engine's own regex engine, which the IntelliJ host does no more
 * than name. The list was true about the line and wrong about the work, which is the same shape as
 * the range markers - a thing already written, filed under a name nobody had matched up.
 *
 * Nothing here is host behaviour, which is exactly why it is worth a test: these are user-facing
 * Vimscript operators that reported "Not implemented yet :(" for want of one line.
 */
class VimRegexServiceTest {

  /** An output channel a test can read back; `:echo` prints there rather than to the status bar. */
  private class RecordingChannel : OutputChannel {
    val lines: MutableList<String> = mutableListOf()

    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {}
    override fun dispose() {}
  }

  private class Session {
    val fake = FakeEditor("one two three")
    val channel = RecordingChannel()
    val host = VimHost(outputPanel = OutputChannelPanelService(channel)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun command(text: String) {
      ":$text".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    val printed: String get() = channel.lines.joinToString("\n").trim()
  }

  @Test
  fun `test the match operator says whether a pattern matches`() {
    val session = Session()
    session.command("echo 'foobar' =~ 'oba'")

    assertEquals("1", session.printed)
  }

  @Test
  fun `test the match operator says when it does not`() {
    val session = Session()
    session.command("echo 'foobar' =~ 'zzz'")

    assertEquals("0", session.printed)
  }

  /** A Vim regex, not a JavaScript one: `\\d` and `\\+` are Vim's spelling. */
  @Test
  fun `test a vim pattern is understood`() {
    val session = Session()
    session.command("echo 'abc123' =~ '\\d\\+'")

    assertEquals("1", session.printed)
  }

  @Test
  fun `test the not-match operator is its opposite`() {
    val session = Session()
    session.command("echo 'foobar' !~ 'oba'")

    assertEquals("0", session.printed)
  }

  /** `split()` cuts a string on every match of a pattern, which is the other caller. */
  @Test
  fun `test split cuts a string on a pattern`() {
    val session = Session()
    session.command("echo split('a1b22c', '\\d\\+')")

    assertEquals("['a', 'b', 'c']", session.printed)
  }
}
