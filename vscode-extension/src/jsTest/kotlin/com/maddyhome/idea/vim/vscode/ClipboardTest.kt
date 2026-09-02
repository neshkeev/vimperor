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

/**
 * Vim's `"+` register, which *is* the system clipboard.
 *
 * The awkward one. VS Code will only talk about the clipboard in promises, and `"+p` is a register
 * read in the middle of a command, so it has to answer now. What it answers with is what the
 * clipboard last said - refreshed when the window regains focus, because a user copying in a
 * browser and switching back is the case that has to work.
 */
class ClipboardTest {

  /** A clipboard whose asynchronous read a test can complete when it chooses. */
  private class DeferredClipboard : SystemClipboard {
    var system: String? = null
    private var mirror: String? = null
    var pendingReads: Int = 0
      private set

    override fun read(): String? = mirror

    override fun write(text: String) {
      mirror = text
      system = text
    }

    override fun refresh() {
      pendingReads++
    }

    /** The promise resolving, which is what a window-focus refresh eventually does. */
    fun completeRefresh() {
      pendingReads = 0
      mirror = system
    }
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val clipboard = DeferredClipboard()
    val errors = mutableListOf<String>()
    val host = VimHost(sink = RecordingSink(errors), clipboard = clipboard).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }

    /** Runs an ex command the way the user would, so the option's change listener fires. */
    fun ex(command: String) {
      type(":$command")
      host.key(fake, "<CR>")
    }

    val content: String get() = fake.document.content
  }

  private class RecordingSink(private val errors: MutableList<String>) : MessageSink {
    override fun message(text: String?) {}
    override fun error(text: String?) { errors += text.orEmpty() }
    override fun status(text: String?) {}
  }

  @Test
  fun `test yanking to the plus register reaches the system clipboard`() {
    val session = Session("hello world")
    session.type("\"+yw")

    assertEquals("hello ", session.clipboard.system)
  }

  @Test
  fun `test pasting from the plus register uses the clipboard`() {
    val session = Session("one")
    session.clipboard.system = " and two"
    session.clipboard.completeRefresh()

    session.type("\"+p")

    // `p` puts after the caret, which is on the first character - so this is Vim being right and
    // not the clipboard being wrong.
    assertEquals("o and twone", session.content)
  }

  @Test
  fun `test text copied elsewhere arrives when the window regains focus`() {
    // The workflow this exists for: copy in a browser, switch to VS Code, paste. The refresh
    // happens on focus, so the answer is there before the key is pressed.
    val session = Session("one")
    session.clipboard.system = " and two"

    session.host.refreshClipboard()
    session.clipboard.completeRefresh()
    session.type("\"+p")

    assertEquals("o and twone", session.content)
  }

  @Test
  fun `test text copied elsewhere is not seen until a refresh`() {
    // The known limit, written down: copying in another application *while* VS Code has focus, and
    // pasting without clicking away and back, pastes what the clipboard said before. Making this
    // right means making paste asynchronous, which is a change to the key path.
    val session = Session("one")
    session.type("\"+yiw")
    session.clipboard.system = "copied elsewhere"

    session.type("\"+p")

    assertEquals("oonene", session.content, "the paste should use the copy Vim made, not the newer one")
  }

  @Test
  fun `test the unnamed register does not touch the clipboard`() {
    // Vim only goes near the system clipboard for `"*` and `"+` unless `'clipboard'` says
    // otherwise. A host that wrote every yank through would replace the user's clipboard on `dd`.
    val session = Session("hello world")
    session.type("yw")

    assertEquals(null, session.clipboard.system, "a plain yank should leave the clipboard alone")
  }

  /**
   * `"*` and `"+` are the same clipboard here, on every platform.
   *
   * Vim's rule is that `"*` is the *selection* only where there is one: under X11 it is the primary
   * selection, which middle-click pastes and which is not the clipboard, and everywhere else the
   * two registers name the same store. This host is the second kind on every platform, because
   * `env.clipboard` is the whole of VS Code's clipboard API and it is the CLIPBOARD selection - so
   * an extension running on X11 still has no way to reach PRIMARY.
   *
   * This used to be asserted against `isXWindow`, which made it pass on both kinds of machine while
   * `"*` on the Linux kind wrote to an in-memory field nothing could read.
   */
  @Test
  fun `test the star register is the clipboard on every platform`() {
    val session = Session("hello world")
    session.type("\"*yw")

    assertEquals("hello ", session.clipboard.system)
  }

  // `'clipboard'`, which decides whether a plain yank goes near any of this.

  /**
   * `unnamed` and `unnamedplus`, the reason anyone sets this option.
   *
   * Both make the *default* register the system clipboard, so `y`, `d`, `c` and `x` all copy out to
   * it without a register prefix. The engine does that by pointing its default register at `*` or
   * `+` when the option changes - and it only knows the option changed because something registered
   * a listener for it. Nothing here did. The option was parsed, stored and read back correctly by
   * `:set clipboard?`, and no yank ever reached the clipboard.
   */
  @Test
  fun `test unnamed sends a plain yank to the system clipboard`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamed")
    session.type("yw")

    assertEquals("hello ", session.clipboard.system)
  }

  @Test
  fun `test unnamedplus sends a plain yank to the system clipboard`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamedplus")
    session.type("yw")

    assertEquals("hello ", session.clipboard.system)
  }

  /** The line from the report, `^=` and both values and the default left on the end of it. */
  @Test
  fun `test the reported config copies a line out`() {
    val session = Session("hello world\nsecond line")
    session.ex("set clipboard^=unnamed,unnamedplus")
    session.type("yy")

    assertEquals("hello world\n", session.clipboard.system, "a linewise yank keeps its newline")
  }

  @Test
  fun `test a delete reaches the clipboard`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamed")
    session.type("dw")

    assertEquals("hello ", session.clipboard.system)
    assertEquals("world", session.content)
  }

  @Test
  fun `test a single character cut reaches the clipboard`() {
    val session = Session("hello")
    session.ex("set clipboard=unnamed")
    session.type("x")

    assertEquals("h", session.clipboard.system)
  }

  /** A named register is the user saying where they want it, and the clipboard is not it. */
  @Test
  fun `test an explicit register keeps the clipboard out of it`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamed")
    session.type("\"ayw")

    assertEquals(null, session.clipboard.system)
  }

  @Test
  fun `test turning the option off stops the copying`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamed")
    session.type("yw")
    session.ex("set clipboard=")
    session.type("wyw")

    assertEquals("hello ", session.clipboard.system, "the second yank should have gone nowhere near it")
  }

  /**
   * A yank goes out and comes back with its type intact.
   *
   * The clipboard holds text and nothing else, so a linewise yank that round-tripped through it as
   * text alone would come back charwise and `p` would put it in the middle of a line.
   */
  @Test
  fun `test a linewise yank still pastes as a line`() {
    val session = Session("one\ntwo")
    session.ex("set clipboard=unnamed")
    session.type("yyp")

    assertEquals("one\none\ntwo", session.content, "charwise would have made this `oonene`")
    assertEquals("one\n", session.clipboard.system, "and the newline is what the clipboard carries")
  }

  @Test
  fun `test text copied elsewhere pastes without a register prefix`() {
    val session = Session("one")
    session.ex("set clipboard=unnamed")
    session.clipboard.system = " and two"
    session.host.refreshClipboard()
    session.clipboard.completeRefresh()

    session.type("p")

    assertEquals("o and twone", session.content)
  }

  /**
   * The values that only mean something to an X server are accepted and do nothing.
   *
   * `autoselect` and its two variants ask for the visual selection to be published as it is made,
   * which is safe under X11 because PRIMARY is a *separate* store from the clipboard - that is the
   * whole point of it. Here there is one clipboard, so doing what they ask would wipe the user's
   * clipboard on every `v`. `html` is a GUI paste format and `exclude:` decides whether to connect
   * to an X server at all. Vim on a build without `+X11` accepts all of them and does nothing,
   * which is what this does.
   */
  @Test
  fun `test autoselect does not publish the visual selection`() {
    val session = Session("hello world")
    session.ex("set clipboard=autoselect,autoselectplus,autoselectml")
    session.type("vll")

    assertEquals(emptyList(), session.errors, "every one of them should be accepted")
    assertEquals(null, session.clipboard.system, "and none of them should have touched the clipboard")
  }

  @Test
  fun `test the X11-only values are accepted alongside the ones that work`() {
    val session = Session("hello world")
    session.ex("set clipboard=unnamed,autoselect,html,exclude:cons\\|linux")
    session.type("yw")

    assertEquals(emptyList(), session.errors)
    assertEquals("hello ", session.clipboard.system, "`unnamed` should still be doing its job")
  }
}
