/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VirtualBufferKind
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.history.VimHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `q:`, `q/` and `q?` - Vim's command-line window, and the last key the engine could reach and this
 * host could not answer.
 *
 * The feature is history in a buffer you can edit: one entry per line, `<CR>` runs the line under
 * the caret against the editor you came from, and the window closes. None of that is host-shaped,
 * and `IjSearchWindowGroup` proved it by having no `com.intellij` import in it at all - it is
 * `SearchWindowGroupBase` in the engine now. What a host owes is one level down: an editor over no
 * file.
 *
 * VS Code has exactly one editable kind of those - an untitled document - so that is what this
 * uses, and reverts before closing so the save prompt never appears.
 *
 * The opener is injected here rather than awaited. VS Code's is a promise, and a test that had to
 * wait for it could not then press a key in the thing it opened.
 */
class CommandLineWindowTest {

  private class RecordingSink : MessageSink {
    val errors: MutableList<String> = mutableListOf()
    override fun message(text: String?) {}
    override fun error(text: String?) { text?.let { errors += it } }
    override fun status(text: String?) {}
  }

  private class Session(text: String = "one\ntwo\nthree\n") {
    val file = FakeEditor(text, path = "/test/file.txt")
    val sink = RecordingSink()

    /** What `q:` was given to put in the window, and the editor it ended up in. */
    var openedWith: String? = null
    var window: FakeEditor? = null

    val host: VimHost = VimHost(
      sink = sink,
      openVirtualBuffer = { content, onOpen ->
        openedWith = content
        val opened = FakeEditor(content, path = "/test/cmdwin")
        window = opened
        onOpen(opened.document)
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(file))
      injector.historyGroup.addEntry(VimHistory.Type.Command, "set number")
      injector.historyGroup.addEntry(VimHistory.Type.Command, "s/one/ONE/")
      commandsExecuted().length = 0
    }

    /** The engine has to be told about the window before a key can be pressed in it. */
    fun enterWindow(): VsCodeEditor = host.editorFor(window!!)

    fun type(text: String) = text.forEach { host.type(file, it.toString()) }
    fun typeInWindow(text: String) = text.forEach { host.type(window!!, it.toString()) }
    fun keyInWindow(notation: String) = host.key(window!!, notation)
    val content: String get() = file.document.content
  }

  // ---- opening ----------------------------------------------------------------------------------

  @Test
  fun `test q colon opens a window holding the command history`() {
    val session = Session()

    session.type("q:")

    assertEquals("set number\ns/one/ONE/", session.openedWith)
  }

  @Test
  fun `test the window knows what kind it is`() {
    val session = Session()
    session.type("q:")

    val window = session.enterWindow()

    assertEquals(VirtualBufferKind.Command, window.getVirtualBufferKind())
    assertTrue(window.isInHistoryWindow())
  }

  @Test
  fun `test q slash opens the search history instead`() {
    val session = Session()
    injector.historyGroup.addEntry(VimHistory.Type.Search, "needle")

    session.type("q/")

    assertEquals("needle", session.openedWith)
    assertTrue(session.enterWindow().getVirtualBufferKind() is VirtualBufferKind.Search)
  }

  /** An ordinary editor is not a command-line window, which is what stops `<CR>` behaving oddly. */
  @Test
  fun `test an ordinary editor is not one`() {
    val session = Session()

    assertEquals(null, session.host.editorFor(session.file).getVirtualBufferKind())
    assertTrue(!session.host.editorFor(session.file).isInHistoryWindow())
  }

  /** `:help cmdwin` - they do not nest, and Vim says so with E1292. */
  @Test
  fun `test it refuses to open a second one`() {
    val session = Session()
    session.type("q:")
    session.openedWith = null

    session.enterWindow()
    session.typeInWindow("q:")

    assertEquals(null, session.openedWith, "a second window should not have been opened")
    assertTrue(session.sink.errors.any { it.contains("E1292") }, "got ${session.sink.errors}")
  }

  // ---- running a line ---------------------------------------------------------------------------

  /**
   * The point of the whole feature: `<CR>` runs the line under the caret against the editor `q:`
   * was opened from, and closes the window.
   */
  @Test
  fun `test enter runs the line against the original editor`() {
    val session = Session()
    session.type("q:")
    session.enterWindow()

    // On the second line - `s/one/ONE/` - and press Enter.
    session.typeInWindow("j")
    session.keyInWindow("<CR>")

    assertEquals("ONE\ntwo\nthree\n", session.content)
  }

  @Test
  fun `test the window is closed without a save prompt`() {
    val session = Session()
    session.type("q:")
    session.enterWindow()

    session.keyInWindow("<CR>")

    assertTrue(
      VsCodeCommands.REVERT_AND_CLOSE in executed(),
      "reverting first is what stops VS Code asking to save a scratch buffer; got ${executed()}",
    )
  }

  @Test
  fun `test a blank line closes and runs nothing`() {
    val session = Session("one\ntwo\nthree\n")
    session.type("q:")
    session.enterWindow()

    session.typeInWindow("GoxD")
    session.keyInWindow("<CR>")

    assertEquals("one\ntwo\nthree\n", session.content, "nothing should have run")
  }

  /** Closing it puts the state back, so the next `q:` opens rather than reporting E1292. */
  @Test
  fun `test it can be opened again afterwards`() {
    val session = Session()
    session.type("q:")
    session.enterWindow()
    session.keyInWindow("<CR>")

    session.openedWith = null
    session.type("q:")

    assertTrue(session.openedWith != null, "the second q: should have opened a window")
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun executed(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
