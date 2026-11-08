/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:copen` — the quickfix list in a buffer, and Enter on a line.
 *
 * The list is read-only, a second `:copen` rewrites it, and Enter jumps in the file the window was
 * opened from. The list stays open. An invalid line is not a jump.
 */
class QuickfixWindowTest {

  private class Session(text: String = "alpha\nbeta\ngamma") {
    val fake = FakeEditor(text)
    val errors: MutableList<String> = mutableListOf()
    val dispatched: MutableList<String> = mutableListOf()

    var opens: Int = 0
    var list: FakeEditor? = null

    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      runCommand = { command, _, onDone ->
        dispatched += command
        onDone(true)
      },
      openVirtualBuffer = { content, onOpen ->
        opens += 1
        val opened = FakeEditor(content, path = "/test/quickfix")
        list = opened
        onOpen(opened.document)
      },
    ).also { it.start() }

    init {
      shownPathsRaw().length = 0
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun typeInList(text: String) = text.forEach { host.type(list!!, it.toString()) }

    fun run(command: String) {
      type(":")
      type(command)
      key("<CR>")
    }

    val listed: String get() = list?.document?.content.orEmpty()

    fun enterList(): VsCodeEditor = host.editorFor(list!!)
    fun keyInList(notation: String) = host.key(list!!, notation)

    fun caretLine(): Int = host.editorFor(fake).primaryCaret().getBufferPosition().line
    fun caretColumn(): Int = host.editorFor(fake).primaryCaret().getBufferPosition().column
  }

  /**
   * The entry on the first line is not where the caret already is, so a jump that did not happen
   * cannot pass.
   */
  private fun openList(session: Session) {
    session.run("""cexpr "/test/buffer.txt:3:2: third"""")
    session.key("gg")
    session.run("""caddexpr "not a place"""")
    session.run("copen")
  }

  @Test
  fun `test enter on an entry jumps in the original file and leaves the list`() {
    val session = Session()
    openList(session)
    session.enterList()
    val before = session.listed
    assertEquals(0, session.caretLine(), "the file caret was moved off the entry before Enter")
    assertEquals("/test/quickfix", shownPaths().last())

    session.keyInList("<CR>")

    assertEquals(2, session.caretLine())
    assertEquals(1, session.caretColumn())
    assertEquals(before, session.listed, "the list stays, and stays as it was")
    assertEquals("/test/buffer.txt", shownPaths().last(), "focus goes to the file the list was opened from: ${shownPaths()}")
    assertEquals("[Quickfix List]", session.enterList().getVirtualBufferKind()?.fileName)
    assertEquals(1, session.opens)
  }

  @Test
  fun `test enter on an invalid line jumps nowhere`() {
    val session = Session()
    openList(session)
    session.enterList()
    session.keyInList("j")
    val shown = shownPaths().size
    val dispatched = session.dispatched.size
    val caret = session.caretLine()

    session.keyInList("<CR>")

    assertEquals(shown, shownPaths().size, "an invalid line does not move the focus: ${shownPaths()}")
    assertEquals(dispatched, session.dispatched.size, "and dispatches nothing: ${session.dispatched}")
    assertEquals(caret, session.caretLine())
    assertTrue(session.errors.isEmpty(), "got ${session.errors}")
  }

  @Test
  fun `test x in the list does not change it`() {
    val session = Session()
    openList(session)
    session.enterList()
    val before = session.listed

    session.keyInList("x")

    assertEquals(before, session.listed)
  }

  /**
   * The location list belongs to the window the list was opened from. Typed in the location
   * window, `:lnext` has to walk that list: the window itself has none.
   */
  @Test
  fun `test lnext in the location window walks the list it is showing`() {
    val session = Session()
    session.run("""lexpr "/test/buffer.txt:1:1: one\n/test/buffer.txt:3:1: three"""")
    session.run("lopen")
    session.enterList()
    assertEquals(0, session.caretLine(), "lexpr left the caret on the first entry")

    session.typeInList(":lnext")
    session.keyInList("<CR>")

    assertEquals(2, session.caretLine(), "the second entry, in the file the window was opened from")
    assertTrue(session.errors.isEmpty(), "got ${session.errors}")
    assertEquals(1, session.opens)
  }

  @Test
  fun `test a second copen does not open another buffer`() {
    val session = Session()
    session.run("""cexpr "a.kt:1:1: first"""")
    session.run("copen")
    session.enterList()

    session.run("""cexpr "b.kt:4:2: other"""")
    session.run("copen")

    assertEquals(1, session.opens, "the opener runs once; the second call rewrites the buffer")
    assertTrue("b.kt:4 col 2: other" in session.listed, "got ${session.listed}")
    assertTrue("a.kt" !in session.listed, "the first list should be gone: ${session.listed}")
  }
}

/** What `showTextDocument` was asked to reveal. Copied from [ActionCommandTest]; the stub is one list. */
private fun shownPathsRaw(): dynamic = js("require('vscode')").window.shownPaths

private fun shownPaths(): List<String> {
  val raw = shownPathsRaw()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
