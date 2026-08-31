/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The running host, and the keys that arrive while it is waiting on VS Code.
 *
 * Undo is where the asynchronous half of this port stops being avoidable. Text edits could be
 * buffered because the engine writes and reads its own copy; `undo` cannot, because VS Code is the
 * one holding the history and the command that unwinds it resolves a promise. What a host can do is
 * refuse to pretend the next keystroke is independent of it, which is what these tests are about.
 */
class VimHostTest {

  /** A VS Code whose commands complete when a test says so, rather than immediately. */
  private class DeferredCommands {
    val dispatched: MutableList<String> = mutableListOf()
    private val callbacks: MutableList<(Boolean) -> Unit> = mutableListOf()

    /** Set to make VS Code reject every command, the way it does for one that does not exist. */
    var rejectEverything: Boolean = false

    fun run(command: String, onDone: (Boolean) -> Unit) {
      dispatched += command
      callbacks += onDone
    }

    /** Lets the commands land, in the order they were asked for. */
    fun complete(perform: (String) -> Unit) {
      val commands = dispatched.toList()
      val waiting = callbacks.toList()
      dispatched.clear()
      callbacks.clear()
      commands.forEach(perform)
      waiting.forEach { it(!rejectEverything) }
    }
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val commands = DeferredCommands()
    val host = VimHost(runCommand = commands::run).also { it.start() }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  @Test
  fun `test typing through the host reaches the document`() {
    val session = Session("")
    session.type("i")
    session.type("hello")
    session.key("<Esc>")

    assertEquals("hello", session.content)
  }

  @Test
  fun `test u asks VS Code to undo`() {
    val session = Session("abc")
    session.type("x")
    assertEquals("bc", session.content)

    session.type("u")

    assertEquals(listOf("undo"), session.commands.dispatched, "u should dispatch VS Code's undo")
  }

  @Test
  fun `test a count on u dispatches once per undo`() {
    val session = Session("abcdef")
    session.type("xxx")
    session.type("3")
    session.type("u")

    assertEquals(listOf("undo", "undo", "undo"), session.commands.dispatched)
  }

  @Test
  fun `test keys pressed while undo is in flight wait for it`() {
    // The race this exists to stop: `u` dispatches, VS Code has not undone yet, and `x` computes
    // against the text that is about to be replaced. Its edit would land first and the undo would
    // then wipe it out - or worse, undo the wrong thing.
    val session = Session("one two")
    session.type("dw")
    assertEquals("two", session.content)

    session.type("u")
    assertTrue(session.host.isWaitingOnHost, "the host should be waiting for VS Code's undo")

    session.type("x")
    assertEquals("two", session.content, "x must not run before the undo lands")

    session.commands.complete { session.fake.undo() }

    // Undo restored "one two", and only then did the waiting `x` run - on the text that was
    // actually there. Running it first would have deleted from "two" and the undo would then have
    // put back "two" wholesale, losing the keystroke and leaving different text.
    assertEquals("ne two", session.content)
    assertFalse(session.host.isWaitingOnHost)
  }

  @Test
  fun `test the buffer is re-read after VS Code changes the document`() {
    // Undo happens outside the buffer entirely, so an editor that kept reading its own copy would
    // compute every later command against text that no longer exists.
    val session = Session("one two")
    session.type("dw")
    assertEquals("two", session.content)

    session.type("u")
    session.commands.complete { session.fake.undo() }

    session.type("x")
    assertEquals("ne two", session.content, "x should apply to the restored text")
  }

  @Test
  fun `test an edit made outside Vim is picked up before the next command`() {
    val session = Session("abc")
    session.fake.document.content = "typed by the user"

    session.type("x")

    assertEquals("yped by the user", session.content)
  }

  @Test
  fun `test one document keeps one editor across VS Code editor objects`() {
    // VS Code hands out a new `TextEditor` for the same document when it is moved to a split. Two
    // Vim editors for one buffer would mean two modes and two sets of carets.
    val session = Session("abc")
    val first = session.host.editorFor(session.fake)
    val again = session.host.editorFor(session.fake)

    assertTrue(first === again, "the same VS Code editor should map to the same Vim editor")
  }

  @Test
  fun `test the mode is reported for the status bar`() {
    val session = Session("abc")
    assertEquals("NORMAL", session.host.modeName())

    session.type("i")
    assertEquals("INSERT", session.host.modeName())

    session.key("<Esc>")
    assertEquals("NORMAL", session.host.modeName())
  }

  // ---- The rest of what only VS Code can do: folds, definitions, windows, tabs.
  //
  // These reach the same runner undo does, and the point of each test is *which* command goes out.
  // Nothing here can check what VS Code then did with it - `executeCommand` reports nothing useful
  // and there is no real editor behind the stub - so the name is the contract.

  @Test
  fun `test zo asks VS Code to unfold`() {
    val session = Session("one\ntwo")
    session.type("zo")
    assertEquals(listOf("editor.unfold"), session.commands.dispatched)
  }

  @Test
  fun `test za asks VS Code to toggle the fold`() {
    val session = Session("one\ntwo")
    session.type("za")
    assertEquals(listOf("editor.toggleFold"), session.commands.dispatched)
  }

  @Test
  fun `test zR unfolds everything`() {
    val session = Session("one\ntwo")
    session.type("zR")
    assertEquals(listOf("editor.unfoldAll"), session.commands.dispatched)
  }

  @Test
  fun `test gd asks VS Code to reveal the definition`() {
    val session = Session("one two")
    session.type("gd")
    // The engine used to write `GotoDeclaration` into the action itself, which is IntelliJ's name
    // for it. It asks the host now, the way it already did for the folds.
    assertEquals(listOf("editor.action.revealDefinition"), session.commands.dispatched)
  }

  @Test
  fun `test C-W s splits the editor group downwards`() {
    val session = Session("one two")
    session.key("<C-W>")
    session.type("s")
    assertEquals(listOf("workbench.action.splitEditorDown"), session.commands.dispatched)
  }

  @Test
  fun `test C-W l moves to the group on the right`() {
    val session = Session("one two")
    session.key("<C-W>")
    session.type("l")
    assertEquals(listOf("workbench.action.focusRightGroup"), session.commands.dispatched)
  }

  @Test
  fun `test C-W k moves to the group above`() {
    val session = Session("one two")
    session.key("<C-W>")
    session.type("k")
    assertEquals(listOf("workbench.action.focusAboveGroup"), session.commands.dispatched)
  }

  @Test
  fun `test C-W o closes the other groups`() {
    val session = Session("one two")
    session.key("<C-W>")
    session.type("o")
    assertEquals(listOf("workbench.action.closeEditorsInOtherGroups"), session.commands.dispatched)
  }

  @Test
  fun `test gt goes to the next editor`() {
    val session = Session("one two")
    session.type("gt")
    assertEquals(listOf("workbench.action.nextEditor"), session.commands.dispatched)
  }

  @Test
  fun `test a count on gT steps back that many editors`() {
    val session = Session("one two")
    session.type("3gT")
    assertEquals(List(3) { "workbench.action.previousEditor" }, session.commands.dispatched)
  }

  // Waiting, and not waiting. The difference is whether the command can change the text.

  @Test
  fun `test changing tab does not hold the keyboard`() {
    val session = Session("one two")
    session.type("gt")
    assertFalse(session.host.isWaitingOnHost, "changing editor does not touch the buffer")
    session.type("x")
    assertEquals("ne two", session.content, "the key ran rather than queueing behind the command")
  }

  @Test
  fun `test folding does hold the keyboard`() {
    val session = Session("one two")
    session.type("zo")
    assertTrue(session.host.isWaitingOnHost, "a fold changes what visibleRanges says, so it waits")
  }

  /**
   * The hazard the rejection branch exists for.
   *
   * A VS Code command that does not exist rejects its promise rather than resolving it. A runner
   * that only listened for success would leave the count above zero and queue every later keystroke
   * behind a command that is never coming back - one typo in an `<Action>` mapping, and the
   * keyboard is gone until the window is reloaded.
   */
  @Test
  fun `test a command that VS Code rejects does not take the keyboard with it`() {
    val session = Session("one two")
    session.commands.rejectEverything = true
    session.type("zo")
    assertTrue(session.host.isWaitingOnHost)

    session.commands.complete { }
    assertFalse(session.host.isWaitingOnHost, "a rejected command has to release the queue too")

    session.type("x")
    assertEquals("ne two", session.content)
  }
}
