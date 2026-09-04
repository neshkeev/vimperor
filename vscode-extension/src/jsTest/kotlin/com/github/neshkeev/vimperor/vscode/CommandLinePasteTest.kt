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
 * `Cmd+V` at the `:` and `/` prompts.
 *
 * Vim already has this: `<C-R>+` inserts the clipboard register into the command line, and the
 * engine implements it with the register arriving as the argument to `<C-R>` rather than through
 * `getchar()` - so it is one of the few command-line features that needed nothing from this host.
 * What a VS Code user reaches for instead is the system paste chord, and that is not a key an
 * extension can be handed: it is a keybinding, and one that has to be claimed only while the prompt
 * is open or it would take paste away from the editor.
 *
 * So the gesture is a command and the work is Vim's, and the only thing in between is the clipboard
 * read - which happens *first* here, unlike everywhere else in this host. See
 * [`the clipboard is re-read before anything is typed`].
 */
class CommandLinePasteTest {

  /** A clipboard that can answer at once or on the test's word, the way a promise does. */
  private class TestClipboard(var system: String? = null, private val deferred: Boolean = false) : SystemClipboard {
    private var mirror: String? = null
    private var waiting: (() -> Unit)? = null

    override fun read(): String? = mirror

    override fun write(text: String) {
      mirror = text
      system = text
    }

    override fun refresh(onDone: () -> Unit) {
      if (deferred) {
        waiting = onDone
      } else {
        mirror = system
        onDone()
      }
    }

    /** The promise resolving. */
    fun completeRefresh() {
      mirror = system
      waiting?.invoke()
      waiting = null
    }
  }

  private class Session(text: String, clipboardHolds: String? = null, deferred: Boolean = false) {
    val fake = FakeEditor(text)
    val clipboard = TestClipboard(clipboardHolds, deferred)
    val display = RecordingDisplay()
    val host = VimHost(commandLineDisplay = display, clipboard = clipboard).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun paste(onDone: () -> Unit = {}) = host.pasteIntoCommandLine(fake, onDone)
    val prompt: String? get() = display.shown
    val content: String get() = fake.document.content
  }

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

  /** Vim's own way, which is what the gesture is built on rather than beside. */
  @Test
  fun `test Ctrl-R plus inserts the clipboard into the prompt`() {
    val session = Session("one two", clipboardHolds = "/tmp/notes.txt")
    session.host.refreshClipboard()
    session.type(":e ")
    session.key("<C-R>")
    session.type("+")

    assertEquals(":e /tmp/notes.txt", session.prompt)
  }

  @Test
  fun `test the paste gesture puts the clipboard in the prompt`() {
    val session = Session("one two", clipboardHolds = "/tmp/notes.txt")
    session.type(":e ")

    session.paste()

    assertEquals(":e /tmp/notes.txt", session.prompt)
  }

  @Test
  fun `test it works at the search prompt too`() {
    // `/` is the same mode, so the same `when` clause claims the chord for it.
    val session = Session("one two three", clipboardHolds = "two")
    session.type("/")

    session.paste()
    assertEquals("/two", session.prompt)

    session.key("<CR>")
    assertEquals(4, session.host.editorFor(session.fake).primaryCaret().offset, "the search should have run")
  }

  /**
   * The clipboard is re-read before anything is typed.
   *
   * Every other register read in this host happens in the middle of a keystroke and has to answer
   * from the mirror, which is refreshed when the window regains focus. A paste is a gesture of its
   * own and can afford to wait for the true answer - which is the difference between pasting what
   * you just copied in the integrated terminal and pasting whatever was there before, because the
   * terminal never takes focus away from the window.
   */
  @Test
  fun `test the clipboard is re-read before anything is typed`() {
    val session = Session("one two")
    session.host.refreshClipboard()
    session.type(":e ")

    // Copied somewhere that did not cost VS Code its focus, so nothing has refreshed the mirror.
    session.clipboard.system = "/tmp/copied-just-now.txt"
    session.paste()

    assertEquals(":e /tmp/copied-just-now.txt", session.prompt)
  }

  @Test
  fun `test nothing is typed until the clipboard answers`() {
    val session = Session("one two", clipboardHolds = "later", deferred = true)
    session.type(":e ")

    var finished = false
    session.paste { finished = true }
    assertEquals(":e ", session.prompt, "the read is still in flight")
    assertTrue(!finished, "and so is the gesture")

    session.clipboard.completeRefresh()

    assertEquals(":e later", session.prompt)
    assertTrue(finished, "the caller is told when it has landed, for the trace and the mode")
  }

  /**
   * Outside the prompt it does nothing at all.
   *
   * The manifest claims the chord only while `vimperor.mode` is `COMMAND`, so in every other mode
   * VS Code's own paste is what runs and this is never reached. The guard is for a context key that
   * has gone stale by a keystroke: `<C-R>` in Normal mode is redo, and a paste that redid something
   * would be a bad way to find that out.
   */
  @Test
  fun `test the gesture does nothing outside the command line`() {
    val session = Session("one two", clipboardHolds = "pasted")
    session.paste()

    assertEquals("one two", session.content, "nothing should have reached the buffer")
    assertEquals("NORMAL", session.host.modeName())
  }

  @Test
  fun `test the manifest claims the chord only while the prompt is open`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so package.json could not be read")
    val manifest = JSON.parse<dynamic>(readText("$root/vscode-extension/package.json"))
    val bindings = manifest.contributes.keybindings as Array<dynamic>
    val paste = bindings.firstOrNull { it.command as? String == "vimperor.pasteInCommandLine" }

    assertTrue(paste != null, "the paste gesture should be bound; nothing else can deliver `Cmd+V`")
    assertEquals("cmd+v", paste.mac as String, "the chord the report asked for")
    // Not plain `ctrl+v` off the Mac: that is Vim's own `<C-V>`, which on the command line inserts
    // the next character literally and is bound to it two dozen lines above.
    assertEquals("ctrl+shift+v", paste.key as String)
    assertTrue(
      "vimperor.mode == 'COMMAND'" in paste.`when` as String,
      "claiming paste in any other mode would take it away from the editor: ${paste.`when`}",
    )
  }
}
