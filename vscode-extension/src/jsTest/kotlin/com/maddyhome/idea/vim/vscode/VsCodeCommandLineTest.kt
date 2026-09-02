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

  /**
   * Twice, which is not the same test as once: the engine's backspace deletes relative to the
   * caret and does not move it, because IntelliJ's command line is a text field that moves its own.
   * With the caret left past the end of the text, the second backspace threw.
   */
  @Test
  fun `test backspace can be pressed more than once`() {
    val session = Session("one")
    session.type(":")
    session.type("sxyz")
    session.key("<BS>")
    session.key("<BS>")
    session.key("<BS>")

    assertEquals(":s", session.display.shown)
  }

  /** And backspacing past the start cancels the prompt, the way Vim does. */
  @Test
  fun `test backspacing an empty command line closes it`() {
    val session = Session("one")
    session.type(":")
    session.type("s")
    session.key("<BS>")
    session.key("<BS>")

    assertNull(session.display.shown, "the prompt should be gone, not empty")
    assertEquals("NORMAL", session.host.modeName())
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
  /**
   * `:copy` finishes, rather than inserting its lines and then giving up.
   *
   * The engine asks the put to reindent what it inserted, which IdeaVim does through IntelliJ's
   * code style. Vim reindents nothing, and VS Code's reindent is an asynchronous command, so this
   * host returns the range unchanged - but until it did, the base class's `TODO` threw, the ex
   * command executor turned that into "Not implemented yet :(", and `:copy` left the caret past the
   * lines it had just written instead of on the first of them.
   */
  @Test
  fun `test copy finishes and leaves the caret on the copied lines`() {
    val session = Session("one\ntwo\nthree\n")
    session.type(":2,3copy 0")
    session.key("<CR>")

    assertEquals("two\nthree\none\ntwo\nthree\n", session.content)
    assertEquals(0, session.caretOffset)
  }

  /** `:move` asks for the same reindent, and used to stop in the same place. */
  @Test
  fun `test move finishes and leaves the caret on the moved line`() {
    val session = Session("one\ntwo\nthree\n")
    session.type(":1move 2")
    session.key("<CR>")

    assertEquals("two\none\nthree\n", session.content)
    assertEquals(4, session.caretOffset)
  }
  /**
   * `:s///c`, which asks before each replacement.
   *
   * This is a modal input rather than a command line - no text buffer, one keystroke is the whole
   * answer - and it had been a `TODO` reading "showInputBox is asynchronous" since the beginning.
   * That was the same wrong guess the command line started from: the engine asks on every keystroke
   * whether a prompt is open and routes the key to its interceptor, so a host only has to draw a
   * label and remember which prompt is up. Three of IdeaVim's fixtures reach this.
   */
  @Test
  fun `test the substitute prompt asks before each replacement`() {
    val session = Session("one and two and three")
    session.type(":%s/and/AND/gc")
    session.key("<CR>")

    assertEquals("Replace with AND (y/n/a/q/l)?", session.display.shown)

    session.type("y")
    assertEquals("one AND two and three", session.content)

    session.type("n")
    assertEquals("one AND two and three", session.content)
    assertNull(session.display.shown, "the prompt should close once there is nothing left to ask")
  }

  /** `a` answers for every remaining match at once. */
  @Test
  fun `test a replaces the rest without asking again`() {
    val session = Session("one and two and three and four")
    session.type(":%s/and/AND/gc")
    session.key("<CR>")
    session.type("a")

    assertEquals("one AND two AND three AND four", session.content)
    assertNull(session.display.shown)
  }

  /** `q` stops, leaving what has already been replaced replaced. */
  @Test
  fun `test q stops the substitution`() {
    val session = Session("one and two and three and four")
    session.type(":%s/and/AND/gc")
    session.key("<CR>")
    session.type("y")
    session.type("q")

    assertEquals("one AND two and three and four", session.content)
    assertNull(session.display.shown)
  }
}
