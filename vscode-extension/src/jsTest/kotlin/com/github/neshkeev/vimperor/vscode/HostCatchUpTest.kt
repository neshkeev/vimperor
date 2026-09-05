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
 * `VimApplication.runAfterHostCatchesUp`: continuing after a VS Code command has landed.
 *
 * The host has always held the user's *keys* while a command was in flight - that is what makes `u`
 * work at all. What it had no answer for was one statement inside a single keystroke depending on
 * the previous one having landed, which is what an extension writing `u` and then re-pasting is
 * doing. IntelliJ's undo returns having finished; VS Code's returns a promise, and the runtime has
 * one thread, so there was nowhere to put the rest of the work.
 *
 * These tests are about the seam rather than about any extension: that a continuation waits when
 * there is something to wait for, that it does *not* wait when there is nothing - which matters
 * more than it sounds, because deferring unconditionally would reorder work against statements
 * written to follow it - and that by the time it runs the buffer has been re-read.
 *
 * [YankRingReplaceTest] is the same seam from the other end, with a real extension on it.
 */
class HostCatchUpTest {

  /**
   * A session whose commands are resolved by the test rather than by the stub.
   *
   * The stub's own runner resolves in the call, which is exactly the case there is nothing to test:
   * it makes VS Code look like IntelliJ. [resolve] is what a real extension host does a tick later.
   */
  private class DeferredSession(text: String) {
    val fake = FakeEditor(text)
    private val waiting: MutableList<(Boolean) -> Unit> = mutableListOf()
    val commands: MutableList<String> = mutableListOf()

    val host = VimHost(
      runCommand = { command, _, onDone ->
        commands += command
        waiting += onDone
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    /** Lets every command in flight finish, as VS Code would when its promise resolves. */
    fun resolve() {
      val pending = waiting.toList()
      waiting.clear()
      pending.forEach { it(true) }
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  @Test
  fun `test a continuation waits for a command in flight`() {
    val session = DeferredSession("one\n")
    val order = mutableListOf<String>()

    session.type("u")
    assertEquals(listOf(VsCodeCommands.UNDO), session.commands)

    injector.application.runAfterHostCatchesUp { order += "after" }
    order += "asked"

    assertEquals(listOf("asked"), order, "the continuation ran before the undo landed")

    session.resolve()
    assertEquals(listOf("asked", "after"), order)
  }

  @Test
  fun `test a continuation with nothing in flight runs at once`() {
    val session = DeferredSession("one\n")
    val order = mutableListOf<String>()

    injector.application.runAfterHostCatchesUp { order += "after" }
    order += "asked"

    // Not "asked", "after". A caller inside a keystroke writes the rest of its work below the call,
    // and deferring when there is nothing to defer for would run that work first.
    assertEquals(listOf("after", "asked"), order)
    assertTrue(session.commands.isEmpty())
  }

  @Test
  fun `test continuations run in the order they were asked for`() {
    val session = DeferredSession("one\n")
    val order = mutableListOf<Int>()

    session.type("u")
    injector.application.runAfterHostCatchesUp { order += 1 }
    injector.application.runAfterHostCatchesUp { order += 2 }
    injector.application.runAfterHostCatchesUp { order += 3 }

    session.resolve()
    assertEquals(listOf(1, 2, 3), order)
  }

  /**
   * The point of waiting at all: a command rewrites the document behind the engine's back, and the
   * continuation is written to act on what it left. The host re-reads every editor before running
   * one, so the engine's buffer already says what VS Code says.
   */
  @Test
  fun `test the buffer has been re-read by the time a continuation runs`() {
    val session = DeferredSession("one\n")
    var seen: String? = null

    session.type("u")
    // What the command did, as far as this test is concerned. A real undo rewrites the document
    // the same way: not through the buffer, so nothing tells the engine until the host re-reads.
    session.fake.document.content = "undone\n"
    session.fake.document.version = session.fake.document.version + 1

    injector.application.runAfterHostCatchesUp {
      seen = session.host.editorFor(session.fake).text().toString()
    }
    session.resolve()

    assertEquals("undone\n", seen)
  }

  /**
   * A continuation that edits reaches VS Code, which nothing else would do for it: the keystroke
   * that asked for the command flushed long before the command landed.
   */
  @Test
  fun `test an edit made in a continuation is written out`() {
    val session = DeferredSession("one\n")

    session.type("u")
    injector.application.runAfterHostCatchesUp {
      val editor = session.host.editorFor(session.fake)
      editor.insertText(editor.primaryCaret(), 0, "x")
    }
    session.resolve()

    assertEquals("xone\n", session.content)
  }
}
