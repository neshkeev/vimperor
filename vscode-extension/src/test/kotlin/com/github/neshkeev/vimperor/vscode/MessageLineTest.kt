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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vim's message line, on the status bar rather than over the editor.
 *
 * The bug this was written for: a `/` that matched nothing opened the output panel over the code -
 * every time, for one line of text - because the sink called `show()` on the channel for anything
 * it was told was an error. The line that was *meant* for the status bar went into the mode
 * indicator's tooltip, so the one path built for this displayed nothing at all.
 *
 * So the two halves are asserted together and neither is interesting alone: what the row says, and
 * that the panel was left exactly as the user had it.
 */
class MessageLineTest {

  /** An output channel that remembers what it was told and whether it was asked to open. */
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

  private class FakeItem : StatusBarItem {
    override var text: String = ""
    override var tooltip: String? = null
    override var color: ThemeColor? = null
    override var backgroundColor: ThemeColor? = null
    var visible: Boolean = false
      private set

    override fun show() {
      visible = true
    }

    override fun hide() {
      visible = false
    }

    override fun dispose() {}

    /** The theme colour id VS Code would paint the row with, or null for the status bar's own. */
    val background: String?
      get() {
        val colour = backgroundColor ?: return null
        return colour.asDynamic().id as String
      }
  }

  /**
   * A host wired the way `activate` wires one, so the keys drive the real engine.
   *
   * The message line is worth driving rather than calling: `/nope<CR>` produces *three* messages in
   * this order - the echo, the wrap notice and the error - and which one a user is left looking at
   * is the whole question. Calling `error(...)` by hand would assert the drawing and skip that.
   */
  private class Session(text: String = "one\ntwo\nthree\n") {
    val fake = FakeEditor(text)
    val channel = RecordingChannel()
    val item = FakeItem()
    val messages = MessageLine(channel, item)
    val prompt = StatusBarPrompt(FakeItem(), FakeItem(), messages)
    val host = VimHost(sink = messages, commandLineDisplay = prompt).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    fun search(pattern: String) {
      type("/")
      type(pattern)
      key("<CR>")
    }
  }

  @Test
  fun `test a pattern that is not there is reported on the status bar`() {
    val session = Session()
    session.search("nothinghere")

    assertEquals("E486: Pattern not found: nothinghere", session.item.text)
    assertTrue(session.item.visible, "the message line has something to say, so it is on screen")
  }

  @Test
  fun `test a failed search does not open the output panel`() {
    val session = Session()
    session.search("nothinghere")

    assertEquals(0, session.channel.shown, "the panel was hidden when the search started")
    assertTrue(
      session.channel.lines.any { it.startsWith("E486") },
      "and the error is still in the log, which is the half worth keeping",
    )
  }

  @Test
  fun `test an error paints the row red`() {
    val session = Session()
    session.search("nothinghere")

    assertEquals("statusBarItem.errorBackground", session.item.background)
  }

  /**
   * Three messages, one row, and the last one wins.
   *
   * The engine says `/nope`, then `search hit BOTTOM, continuing at TOP`, then the error - so a row
   * that kept the first would report a wrap as the outcome of a search that found nothing.
   */
  @Test
  fun `test the error is what survives the messages before it`() {
    val session = Session()
    session.search("nothinghere")

    assertTrue(session.channel.lines.any { it.startsWith("/nothinghere") }, "the echo was said")
    assertTrue(session.channel.lines.any { it.contains("hit BOTTOM") }, "and so was the wrap")
    assertEquals("E486: Pattern not found: nothinghere", session.item.text, "and the error is what shows")
  }

  @Test
  fun `test a search that finds something leaves the pattern on the line`() {
    val session = Session()
    session.search("three")

    assertEquals("/three", session.item.text)
    assertNull(session.item.background, "nothing went wrong, so the row is the status bar's own colour")
  }

  /** A row with nothing on it is hidden rather than blank, so it takes up no width. */
  @Test
  fun `test the line is hidden until Vim says something`() {
    val session = Session()

    assertEquals("", session.item.text)
    assertFalse(session.item.visible)
  }

  /**
   * Vim's command line draws over the message line, so opening one takes the last message back.
   *
   * Without this the `E486` from a mistyped search would sit in red beside the `:noh` being typed
   * to get rid of it - and would stay there afterwards, because a command that reports nothing
   * writes nothing over it.
   */
  @Test
  fun `test opening a command line takes the message back`() {
    val session = Session()
    session.search("nothinghere")
    assertTrue(session.item.visible)

    session.type(":")

    assertEquals("", session.item.text)
    assertFalse(session.item.visible)
    assertNull(session.item.background, "and the red goes with it")
  }

  /**
   * The redraw a prompt does on every keystroke must not wipe what the same command just said.
   *
   * `/pattern` reports while its own prompt is still open, so a clear on every `show` rather than
   * on the opening one would leave the row empty after every search.
   */
  @Test
  fun `test a message survives the prompt it was reported from`() {
    val session = Session()
    session.search("three")

    assertEquals("/three", session.item.text)
  }

  /**
   * A `:` command's report outlives the prompt it was typed at, and an error still reads as one.
   *
   * `:s` goes through `showStatusBarMessage`, the engine's legacy one-liner and the path that used
   * to end in the mode indicator's tooltip. It carries reports and errors alike and the text cannot
   * say which is which, so the host asks `isError()` - set by the engine immediately before this
   * message and cleared at the top of every keystroke.
   */
  @Test
  fun `test an error from a colon command outlives its prompt and is red`() {
    val session = Session()
    session.type(":")
    session.type("%s/nothinghere/x/")
    session.key("<CR>")

    assertEquals("E486: Pattern not found: nothinghere", session.item.text)
    assertTrue(session.item.visible)
    assertEquals("statusBarItem.errorBackground", session.item.background)
  }

  /**
   * `$(name)` in status bar text is drawn as an icon and VS Code offers no escape for it.
   *
   * A search for a literal `$(` is ordinary, and reporting its failure with a picture in the middle
   * of the pattern would be a bug nobody would think to look for. A zero-width space after the
   * dollar stops the parse and is invisible.
   */
  @Test
  fun `test a dollar bracket in a message is not drawn as an icon`() {
    val item = FakeItem()
    val reported = "E486: Pattern not found: \$(zap)"
    MessageLine(RecordingChannel(), item).error(reported)

    assertEquals("E486: Pattern not found: \$\u200b(zap)", item.text)
    assertFalse(item.text.contains("\$("), "the codicon syntax is broken up")
    assertEquals(reported, item.tooltip, "and the tooltip has it as typed")
  }

  /** One row, so the line breaks an `:echoerr` can carry become word breaks rather than nothing. */
  @Test
  fun `test a message of several lines is flattened onto the row`() {
    val item = FakeItem()
    MessageLine(RecordingChannel(), item).message("first\nsecond")

    assertEquals("first second", item.text)
    assertEquals("first\nsecond", item.tooltip, "the whole of it is still readable on hover")
  }

  /** A long message would push the rest of the status bar off the screen, so the row takes a cut. */
  @Test
  fun `test a very long message is shortened and kept whole in the tooltip`() {
    val item = FakeItem()
    val long = "E486: Pattern not found: " + "x".repeat(200)
    MessageLine(RecordingChannel(), item).error(long)

    assertTrue(item.text.length <= 96, "it fits: ${item.text.length}")
    assertTrue(item.text.endsWith("…"), "and says that it was cut")
    assertEquals(long, item.tooltip)
  }

  /**
   * `clearStatusBarMessage` means clear; a null from the other two means the caller had nothing.
   *
   * The difference is not decoration. `showErrorMessage(editor, e.message)` hands over whatever an
   * exception carried, which is null often enough, and wiping the row for one of those would throw
   * away a message the user has not read yet.
   */
  @Test
  fun `test only the status path treats null as a clear`() {
    val item = FakeItem()
    val line = MessageLine(RecordingChannel(), item)

    line.error("E486: Pattern not found: nope")
    line.error(null)
    assertEquals("E486: Pattern not found: nope", item.text, "an error with no text says nothing")

    line.status(null)
    assertEquals("", item.text)
    assertFalse(item.visible)
  }
}
