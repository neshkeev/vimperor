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
 * What a search says, and how many times it says it.
 *
 * Reported from a real window: `n` in a file with a single match printed "search hit BOTTOM,
 * continuing at TOP" twice. Nothing was wrong with the message - the search really did run twice.
 *
 * Vim's `nv_next` repeats the search with `count + 1` when `n` left the caret where it found it,
 * so that `/pat/e` on the last character of the buffer cannot get stuck. It guards that on the
 * search *not* having wrapped:
 *
 * ```c
 * int wrapped = FALSE;
 * int i = normal_search(cap, cap->cmdchar, NULL, 0, &wrapped);
 * if (i == 1 && !wrapped && EQUAL_POS(old, curwin->w_cursor))
 * ```
 *
 * The engine had the `EQUAL_POS` half and not the `!wrapped` half, and a file with one match
 * satisfies `EQUAL_POS` every time: the search runs off the end, comes back to the only match there
 * is, and lands on the caret it started from. So every `n` searched the whole file twice.
 *
 * It was only ever visible here. Vim and IntelliJ both draw this on one row that the next message
 * overwrites; an output channel is a log, and a log shows you the repeat.
 */
class SearchMessagesTest {

  private class RecordingSink : MessageSink {
    val said: MutableList<String> = mutableListOf()
    override fun message(text: String?) { said += text.orEmpty() }
    override fun error(text: String?) { said += text.orEmpty() }
    override fun status(text: String?) {}
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val caret: Int get() = (host.editorFor(fake)).primaryCaret().offset

    /** What was said since the last time this was asked, which keeps the search itself out of it. */
    fun saidSince(): List<String> = sink.said.toList().also { sink.said.clear() }
  }

  private val wrapped = "search hit BOTTOM, continuing at TOP"

  private fun searchFor(pattern: String, text: String): Session {
    val session = Session(text)
    session.type("/$pattern")
    session.key("<CR>")
    session.saidSince()
    return session
  }

  @Test
  fun `test n on the only match says it wrapped once`() {
    val session = searchFor("beta", "alpha\nbeta\ngamma")

    session.key("n")

    assertEquals(listOf("/beta", wrapped), session.saidSince())
  }

  @Test
  fun `test n on the only match stays on it`() {
    val text = "alpha\nbeta\ngamma"
    val session = searchFor("beta", text)

    session.key("n")

    assertEquals(text.indexOf("beta"), session.caret)
  }

  /** `N` is the same command backwards, and had the same doubled message. */
  @Test
  fun `test N on the only match says it wrapped once`() {
    val session = searchFor("beta", "alpha\nbeta\ngamma")

    session.key("N")

    assertEquals(listOf("?beta", "search hit TOP, continuing at BOTTOM"), session.saidSince())
  }

  /** Two matches, so `n` moves and never wraps: nothing to say beyond the search itself. */
  @Test
  fun `test n with somewhere to go says nothing about wrapping`() {
    val session = searchFor("beta", "alpha\nbeta\nbeta\ngamma")

    session.key("n")

    assertEquals(listOf("/beta"), session.saidSince())
  }

  /** ...and once it reaches the last one, wrapping is real and is reported. */
  @Test
  fun `test n past the last match wraps to the first`() {
    val text = "alpha\nbeta\nbeta\ngamma"
    val session = searchFor("beta", text)
    session.key("n")
    session.saidSince()

    session.key("n")

    assertEquals(listOf("/beta", wrapped), session.saidSince())
    assertEquals(text.indexOf("beta"), session.caret)
  }
}
