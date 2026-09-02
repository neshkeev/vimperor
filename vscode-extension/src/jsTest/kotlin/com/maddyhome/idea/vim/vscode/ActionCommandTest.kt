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
import kotlin.test.assertTrue

/**
 * `:action`, `:actionlist` and `<Action>()` - Vim's door onto everything the editor can do.
 *
 * IdeaVim's three, in the terms VS Code uses. IntelliJ has actions and VS Code has commands, and
 * the difference that matters is not the word: an IntelliJ action can be looked up, run and
 * answered for synchronously, and a VS Code command can only be dispatched at a promise. So `:action`
 * reports "not found" from a list fetched once at activation, and reports success from the fact
 * that it asked.
 *
 * [`the list is what decides whether a name is an action`] is the one worth reading twice. Refusing
 * a name Vim has not heard of is the whole value of `:action` over a raw dispatch - a typo says so
 * at the `:` prompt - and refusing one only because a promise had not landed would be a lie, so the
 * unloaded case accepts everything.
 */
class ActionCommandTest {

  private class RecordingChannel : OutputChannel {
    val lines: MutableList<String> = mutableListOf()

    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {}
    override fun dispose() {}
  }

  private class Session(text: String = "hello world", known: List<String>? = SOME_COMMANDS) {
    val fake = FakeEditor(text)
    val channel = RecordingChannel()
    val dispatched: MutableList<String> = mutableListOf()
    val errors: MutableList<String> = mutableListOf()

    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      runCommand = { command, _, onDone -> dispatched += command; onDone(true) },
      outputPanel = OutputChannelPanelService(channel),
    ).also { it.start() }

    init {
      // What activation does when `commands.getCommands` resolves. Null models the window where it
      // has not resolved yet, which is a real state and the one the fallback is for.
      known?.let { host.rememberActions(it) }
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    fun run(command: String) {
      type(":")
      type(command)
      key("<CR>")
    }

    val printed: String get() = channel.lines.joinToString("\n")
  }

  private companion object {
    /** A plausible slice of what a real window answers, in the order it does not answer it. */
    val SOME_COMMANDS = listOf(
      "workbench.action.showCommands",
      "editor.action.formatDocument",
      "git.commitStagedAll",
      "git.pull",
      "_internalOne",
      "cursorDown",
    )
  }

  // `:action`

  @Test
  fun `test action runs the VS Code command it names`() {
    val session = Session()
    session.run("action editor.action.formatDocument")

    assertEquals(listOf("editor.action.formatDocument"), session.dispatched)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test action trims what it was given`() {
    // The `:` prompt hands the argument over with the space after the command name still on it.
    val session = Session()
    session.run("action   git.pull  ")

    assertEquals(listOf("git.pull"), session.dispatched)
  }

  /**
   * The list is what decides whether a name is an action.
   *
   * `:action GotoClass` in a config written for IdeaVim names an IntelliJ action, and this is what
   * makes that say so instead of silently doing nothing.
   */
  @Test
  fun `test action reports a name this VS Code does not have`() {
    val session = Session()
    session.run("action GotoClass")

    assertEquals(emptyList(), session.dispatched, "nothing should have been sent to VS Code")
    assertEquals(listOf("Action not found: GotoClass"), session.errors)
  }

  @Test
  fun `test action accepts anything until the list has arrived`() {
    // Activation asks VS Code what it can do and the answer comes back over a promise. Refusing a
    // name because that promise has not landed would be a lie: the command may well exist.
    val session = Session(known = null)
    session.run("action anything.at.all")

    assertEquals(listOf("anything.at.all"), session.dispatched)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test an internal command is still an action`() {
    // VS Code's `filterInternal` drops the underscore-prefixed ones, and this host asks for the
    // unfiltered list: "internal" is a naming convention, not a promise that it will not run.
    val session = Session()
    session.run("action _internalOne")

    assertEquals(listOf("_internalOne"), session.dispatched)
  }

  @Test
  fun `test an Action mapping runs the same command`() {
    // `<Action>(...)` is the form IdeaVim tells people to prefer, and it goes through the same
    // executor by a different door - the key handler rather than the `:` prompt.
    val session = Session()
    session.run("nmap <Leader>f <Action>(editor.action.formatDocument)")
    session.type("\\f")

    assertEquals(listOf("editor.action.formatDocument"), session.dispatched)
  }

  // `:actionlist`

  @Test
  fun `test actionlist prints every command, sorted`() {
    val session = Session()
    session.run("actionlist")

    val listed = session.printed.lines().filter { it in SOME_COMMANDS }
    assertEquals(SOME_COMMANDS.sorted(), listed, "every command, in an order a reader can scan")
    assertTrue("--- Actions ---" in session.printed, "IdeaVim's header, which is the engine's string")
    assertTrue("--- 6 of 6 ---" in session.printed, "and how much of the list is showing")
  }

  @Test
  fun `test actionlist filters by what it was given`() {
    val session = Session()
    session.run("actionlist git")

    assertTrue("git.pull" in session.printed)
    assertTrue("cursorDown" !in session.printed, "the ones that do not match should be gone")
    assertTrue("--- 2 of 6 ---" in session.printed)
  }

  /** IdeaVim's `*`, which splits the pattern rather than matching anything. */
  @Test
  fun `test a star splits the pattern into pieces that must all appear`() {
    val session = Session()
    session.run("actionlist git*commit")

    assertTrue("git.commitStagedAll" in session.printed)
    assertTrue("git.pull" !in session.printed, "`pull` has no `commit` in it")
    assertTrue("--- 1 of 6 ---" in session.printed)
  }

  @Test
  fun `test the filter ignores case`() {
    val session = Session()
    session.run("actionlist FORMATDOCUMENT")

    assertTrue("editor.action.formatDocument" in session.printed)
    assertTrue("--- 1 of 6 ---" in session.printed)
  }

  @Test
  fun `test actionl is enough of it`() {
    // `actionl[ist]`, the way IdeaVim spells it - so `:actionl` resolves and `:action` does not.
    val session = Session()
    session.run("actionl git")

    assertTrue("git.pull" in session.printed)
    assertEquals(emptyList(), session.dispatched, "`:actionl` is not `:action` with an argument")
  }

  @Test
  fun `test actionlist says so when it has not been told anything`() {
    val session = Session(known = null)
    session.run("actionlist")

    assertTrue(
      "has not been told what commands this VS Code has" in session.printed,
      "an empty list would read as an editor that can do nothing: ${session.printed}",
    )
  }
}
