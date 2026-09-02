/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:g/pattern/command` - run a command on every matching line.
 *
 * It reported "Not implemented yet :(" until now, and nothing had caught that. The ex command sweep
 * types every registered command bare, and a bare `:g` is a Vim error before it reaches anything -
 * so the sweep saw a working command. The fixture harness skips `GlobalCommandTest` outright,
 * because that file defines its own `doTest` which takes an ex command where `VimTestCase`'s takes
 * keys.
 *
 * What it needed was a marker that follows edits, so that the lines matched before the first
 * deletion are still findable after it. This host had those all along under a different name.
 */
class GlobalCommandTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  private class RecordingSink : MessageSink {
    val said: MutableList<String> = mutableListOf()
    override fun message(text: String?) { said += text.orEmpty() }
    override fun error(text: String?) { said += text.orEmpty() }
    override fun status(text: String?) { said += text.orEmpty() }
  }

  @Test
  fun `test g deletes every matching line`() {
    val session = Session("one\ntwo\nthree\ntwo again\nfour")
    session.type(":g/two/d")
    session.key("<CR>")

    assertEquals("one\nthree\nfour", session.content)
    assertTrue(session.said().none { it.contains("Not implemented") }, "said ${session.said()}")
  }

  /** `:v` is `:g!` - every line the pattern does *not* match. */
  @Test
  fun `test v deletes every line that does not match`() {
    val session = Session("one\ntwo\nthree\ntwo again\nfour")
    session.type(":v/two/d")
    session.key("<CR>")

    assertEquals("two\ntwo again", session.content)
  }

  /**
   * The markers are the point: `:g/x/d` deletes lines from the top down, and every line it has
   * still to visit has moved by the time it gets there.
   */
  @Test
  fun `test the lines still to visit move as earlier ones are deleted`() {
    val session = Session("x\na\nx\nb\nx\nc")
    session.type(":g/x/d")
    session.key("<CR>")

    assertEquals("a\nb\nc", session.content)
  }

  @Test
  fun `test g can run a substitution on the matching lines`() {
    val session = Session("keep one\ndrop one\nkeep one")
    session.type(":g/keep/s/one/two/")
    session.key("<CR>")

    assertEquals("keep two\ndrop one\nkeep two", session.content)
  }

  private fun Session.said(): List<String> = sink.said
}
