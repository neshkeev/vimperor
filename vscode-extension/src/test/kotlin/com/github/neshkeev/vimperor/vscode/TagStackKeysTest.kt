/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.github.neshkeev.vimperor.tags.Tags
import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `<C-]>` and `<C-T>`, the two keys the tag stack is for.
 *
 * The stack, a ctags reader and nine ex commands were here before any of this; neither key was
 * wired to them. `<C-]>` jumped without leaving a trail, and `<C-T>` was bound alongside `<C-O>` to
 * the *jump list*, which is a different stack - `:h CTRL-T` pops the tag stack. The two happened to
 * agree right after a jump, which is why it looked like it worked.
 */
class TagStackKeysTest {

  private class Session(text: String = "call findUser here", path: String = "/work/main.kt") {
    val fake = FakeEditor(text, path)
    val dispatched: MutableList<String> = mutableListOf()
    val errors: MutableList<String> = mutableListOf()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      runCommand = { command, _, onDone -> dispatched += command; onDone(true) },
    ).also { it.start() }

    val editor = host.editorFor(fake)

    init {
      Tags.reset()
      KeyHandler.getInstance().fullReset(editor)
    }

    fun key(notation: String) = host.key(fake, notation)
    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun stack() = Tags.stack(editor.projectId)
  }

  @Test
  fun `test C-] pushes the word under the caret onto the tag stack`() {
    val session = Session()
    session.key("<Esc>")
    session.type("w")
    session.key("<C-]>")

    assertEquals(listOf("findUser"), session.stack().map { it.tagName })
    assertTrue(
      session.dispatched.contains("editor.action.revealDefinition"),
      "expected a go-to-definition, got ${session.dispatched}",
    )
  }

  /** The entry remembers where the jump started, which is the whole point of pushing it. */
  @Test
  fun `test the entry remembers where the jump started`() {
    val session = Session()
    session.key("<Esc>")
    session.type("w")
    session.key("<C-]>")

    val entry = session.stack().single()
    assertEquals("/work/main.kt", entry.fromPath.substringAfter("://", entry.fromPath))
    assertEquals(0, entry.fromLine)
    assertEquals(5, entry.fromColumn)
  }

  /** `<C-T>` walks back down it, which is what it could never do while it was bound to the jump list. */
  @Test
  fun `test C-T returns to where C-] was pressed`() {
    val session = Session()
    session.key("<Esc>")
    session.type("w")
    session.key("<C-]>")
    // The host's definition provider is a stub here, so nothing moved the caret; move it ourselves
    // to somewhere the pop has to undo.
    session.type("$")
    session.key("<C-T>")

    assertEquals(5, session.editor.currentCaret().getBufferPosition().column)
  }

  /** Vim's E555: nothing has been pushed, so there is nowhere to go. */
  @Test
  fun `test C-T on an empty stack reports E555`() {
    val session = Session()
    session.key("<Esc>")
    session.key("<C-T>")

    assertTrue(session.errors.any { it.contains("E555") }, "expected E555, got ${session.errors}")
  }

  /** `:set notagstack` and a tag jump stops leaving a trail. */
  @Test
  fun `test notagstack stops the push`() {
    val session = Session()
    session.key("<Esc>")
    ":set notagstack".forEach { session.host.type(session.fake, it.toString()) }
    session.key("<CR>")
    session.type("w")
    session.key("<C-]>")

    assertEquals(emptyList(), session.stack().map { it.tagName })
  }

  /** `<C-O>` still walks the jump list. It lost a neighbour, not its own binding. */
  @Test
  fun `test C-O still walks the jump list`() {
    val session = Session("one\ntwo\nthree\nfour\nfive")
    session.key("<Esc>")
    session.type("G")
    session.type("gg")

    session.key("<C-O>")

    assertEquals(4, session.editor.currentCaret().getBufferPosition().line)
  }
}
