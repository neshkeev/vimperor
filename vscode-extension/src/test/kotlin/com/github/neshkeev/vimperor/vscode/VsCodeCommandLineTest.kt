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

    var caret: Int? = null
      private set

    override fun show(text: String, caret: Int?) {
      shown = text
      this.caret = caret
    }

    var matches: String? = null
      private set

    override fun showMatches(line: String?) {
      matches = line
    }

    override fun hide() {
      shown = null
      caret = null
      matches = null
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

  // The caret, which is the difference between typing a command and editing one.

  /**
   * Where the caret is, told apart from what the line says.
   *
   * A status bar item is text and takes no styling, so the caret has to be a character spliced into
   * the string - and that would have made every assertion in this file read around a glyph. The
   * display is handed the two separately instead, which is also the honest shape: where a caret
   * goes is the drawing's business, and something painting this into the editor one day could draw
   * a real block over the character the way Vim does.
   */
  @Test
  fun `test the caret starts after what has been typed`() {
    val session = Session("one two")
    session.type(":")
    session.type("set nu")

    assertEquals(":set nu", session.display.shown)
    assertEquals(7, session.display.caret, "at the end, which is where the next character goes")
  }

  @Test
  fun `test Left and Right move it`() {
    val session = Session("one two")
    session.type(":set nu")
    session.key("<Left>")
    session.key("<Left>")

    assertEquals(5, session.display.caret)

    session.key("<Right>")

    assertEquals(6, session.display.caret)
  }

  /**
   * The caret is what the next character is typed at, which is the point of moving it.
   *
   * This worked before the caret was drawn - the engine's command-line motions are assignments to
   * an offset and `handleKey` has always inserted there. What was missing was any way to see where
   * that offset was, which made editing a typed command a guess.
   */
  @Test
  fun `test typing goes in at the caret`() {
    val session = Session("one two")
    session.type(":set nu")
    session.key("<Left>")
    session.key("<Left>")
    session.type("relative")

    assertEquals(":set relativenu", session.display.shown)
    assertEquals(13, session.display.caret)
  }

  @Test
  fun `test Home and End go to the ends`() {
    val session = Session("one two")
    session.type(":set nu")

    session.key("<Home>")
    assertEquals(1, session.display.caret, "after the `:`, which is a label and not text")

    session.key("<End>")
    assertEquals(7, session.display.caret)
  }

  @Test
  fun `test Ctrl-B and Ctrl-E are the same two`() {
    // Vim's own names for them, and the reason `<C-E>` on the command line is not a scroll.
    val session = Session("one two")
    session.type(":set nu")

    session.key("<C-B>")
    assertEquals(1, session.display.caret)

    session.key("<C-E>")
    assertEquals(7, session.display.caret)
  }

  @Test
  fun `test Shift-Left and Shift-Right move by a word`() {
    val session = Session("one two")
    session.type(":set number")

    session.key("<S-Left>")
    assertEquals(5, session.display.caret, "to the start of `number`")

    session.key("<S-Right>")
    assertEquals(11, session.display.caret)
  }

  @Test
  fun `test backspace takes the character before the caret`() {
    val session = Session("one two")
    session.type(":set nu")
    session.key("<Left>")
    session.key("<BS>")

    // The caret is between `n` and `u`, so backspace takes the `n` and not the `u`.
    assertEquals(":set u", session.display.shown)
    assertEquals(5, session.display.caret, "and the caret comes back with the text")
  }

  @Test
  fun `test recalling a command puts the caret at its end`() {
    val session = Session("one two")
    session.type(":set nu")
    session.key("<CR>")
    session.type(":")
    session.key("<Up>")

    assertEquals(":set nu", session.display.shown)
    assertEquals(7, session.display.caret)
  }

  /**
   * A pending `<C-R>` draws its own caret, so this one gets out of the way.
   *
   * Vim replaces the cursor with the `"` while it waits for a register name. Two markers beside
   * each other would say the cursor is somewhere it is not.
   */
  @Test
  fun `test a prompt character stands in for the caret`() {
    val session = Session("one two")
    session.type(":e ")
    session.key("<C-R>")

    assertEquals(":e \"", session.display.shown)
    assertEquals(null, session.display.caret)
  }

  // What a status bar actually shows, which is the one place the caret becomes a character.

  @Test
  fun `test the status bar splices a caret into the line`() {
    val item = FakeStatusBarItem()
    val prompt = StatusBarPrompt(item, FakeStatusBarItem())

    prompt.show(":set nu", 5)

    assertEquals(":set \u258fnu", item.text)
  }

  @Test
  fun `test the status bar shows a trailing space that would otherwise be invisible`() {
    // `:e ` and `:e` are different commands and looked identical on the status bar.
    val item = FakeStatusBarItem()
    val prompt = StatusBarPrompt(item, FakeStatusBarItem())

    prompt.show(":e ", 3)

    assertEquals(":e \u258f", item.text)
  }

  @Test
  fun `test the status bar draws no caret when there is none`() {
    val item = FakeStatusBarItem()
    val prompt = StatusBarPrompt(item, FakeStatusBarItem())

    prompt.show(":e \"", null)

    assertEquals(":e \"", item.text)
  }

  private class FakeStatusBarItem : StatusBarItem {
    override var text: String = ""
    override var tooltip: String? = null
    override var color: ThemeColor? = null
    var visible: Boolean = false
      private set

    override fun show() {
      visible = true
    }

    override fun hide() {
      visible = false
    }

    override fun dispose() {}
  }
}
