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
 * Tab completion at the `:` prompt, and `:action` in particular.
 *
 * The engine has the whole of this already - a completion session that Tab and Shift-Tab walk, a
 * parser that says whether the caret is in a command name or its argument, and a type per command
 * saying what its argument completes against. It knew about file paths and nothing else, and it is
 * the engine's own `:action` whose argument had nowhere to look: both hosts answer
 * `VimActionExecutor.getActionIdList`, which is a prefix query, so an `ACTION` type is all that was
 * missing and IdeaVim gets `:action` completion out of the same change.
 *
 * What this host had to build is the part that shows them. `showCompletionBar` and its two
 * companions are no-ops on `VimCommandLine`, because IdeaVim draws a panel over the editor and a
 * host without one is expected to say nothing - but cycling through matches you cannot see is a
 * poor version of completion.
 */
class CommandLineCompletionTest {

  private class RecordingDisplay : CommandLineDisplay {
    var shown: String? = null
      private set
    var matches: String? = null
      private set

    override fun show(text: String, caret: Int?) {
      shown = text
    }

    override fun showMatches(line: String?) {
      matches = line
    }

    override fun hide() {
      shown = null
      matches = null
    }
  }

  private class Session(known: List<String> = SOME_COMMANDS) {
    val fake = FakeEditor("hello world")
    val display = RecordingDisplay()
    val dispatched: MutableList<String> = mutableListOf()
    val host = VimHost(
      commandLineDisplay = display,
      runCommand = { command, _, onDone -> dispatched += command; onDone(true) },
    ).also { it.start() }

    init {
      host.rememberActions(known)
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  private companion object {
    val SOME_COMMANDS = listOf(
      "workbench.action.showCommands",
      "editor.action.formatDocument",
      "git.commitStagedAll",
      "git.pull",
      "cursorDown",
    )
  }

  @Test
  fun `test Tab completes an action prefix`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")

    assertEquals(":action git.commitStagedAll", session.display.shown)
  }

  @Test
  fun `test the matches are shown, with the one Tab landed on marked`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")

    assertEquals("1/2  [git.commitStagedAll]  git.pull", session.display.matches)
  }

  @Test
  fun `test Tab again takes the next one`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")
    session.key("<Tab>")

    assertEquals(":action git.pull", session.display.shown)
    assertEquals("2/2  git.commitStagedAll  [git.pull]", session.display.matches)
  }

  @Test
  fun `test Shift-Tab walks back`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")
    session.key("<S-Tab>")

    assertEquals(":action git.pull", session.display.shown, "back past the first one wraps to the last")
  }

  /** One match is not a list; Vim fills it in and says nothing. */
  @Test
  fun `test a single match is filled in without a list`() {
    val session = Session()
    session.type(":action cur")
    session.key("<Tab>")

    assertEquals(":action cursorDown", session.display.shown)
    assertEquals(null, session.display.matches)
  }

  // `:set`, whose argument is a list of option names.

  /** The reported case: the abbreviation is a way of writing the option, so Tab spells it out. */
  @Test
  fun `test Tab completes an option name`() {
    val session = Session()
    session.type(":set syn")
    session.key("<Tab>")

    assertEquals(":set syntax", session.display.shown)
  }

  @Test
  fun `test Tab cycles when more than one option starts that way`() {
    val session = Session()
    session.type(":set nu")
    session.key("<Tab>")
    assertEquals(":set number", session.display.shown)

    session.key("<Tab>")
    assertEquals(":set numberwidth", session.display.shown)
  }

  /**
   * `:set` takes a *list*, so only the word being typed is replaced.
   *
   * The parser hands the whole argument to completion, because for `:edit my file.txt` the whole
   * argument is the file name. `:set` is the other kind, and this is the difference.
   */
  @Test
  fun `test only the option being typed is replaced`() {
    val session = Session()
    session.type(":set number rel")
    session.key("<Tab>")

    assertEquals(":set number relativenumber", session.display.shown)
  }

  /** `no` in front of a boolean option is how you write one, so it completes as one. */
  @Test
  fun `test the no prefix completes boolean options`() {
    val session = Session()
    session.type(":set norel")
    session.key("<Tab>")

    assertEquals(":set norelativenumber", session.display.shown)
  }

  @Test
  fun `test inv completes the same way`() {
    val session = Session()
    session.type(":set invrel")
    session.key("<Tab>")

    assertEquals(":set invrelativenumber", session.display.shown)
  }

  /** A word that has already said what it wants is not a name being typed. */
  @Test
  fun `test a question, a toggle and an assignment are left alone`() {
    for (typed in listOf(":set syn?", ":set syn!", ":set syntax=ja")) {
      val session = Session()
      session.type(typed)
      session.key("<Tab>")

      assertEquals(typed.removePrefix(":"), session.display.shown?.removePrefix(":"), "`$typed` should not complete")
    }
  }

  /** `:setlocal` and `:setglobal` take the same names. */
  @Test
  fun `test setlocal and setglobal complete options too`() {
    for (command in listOf("setlocal", "setglobal")) {
      val session = Session()
      session.type(":$command syn")
      session.key("<Tab>")

      assertEquals(":$command syntax", session.display.shown)
    }
  }

  @Test
  fun `test a prefix that matches nothing changes nothing`() {
    val session = Session()
    session.type(":action nope")
    session.key("<Tab>")

    assertEquals(":action nope", session.display.shown)
    assertEquals(null, session.display.matches)
  }

  /**
   * Typing past a completion puts the list away.
   *
   * The engine drops its own session by comparing the text it last set against what is there now,
   * but nothing tells the bar - so the matches for `git` would sit under `:action gitx` while Tab
   * had already forgotten them.
   */
  @Test
  fun `test typing past a completion hides the list`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")
    assertTrue(session.display.matches != null)

    session.type("x")

    assertEquals(null, session.display.matches)
  }

  @Test
  fun `test running the command puts the list away`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")
    session.key("<CR>")

    assertEquals(null, session.display.matches)
    assertEquals(listOf("git.commitStagedAll"), session.dispatched, "and the completed action runs")
  }

  @Test
  fun `test cancelling puts the list away`() {
    val session = Session()
    session.type(":action git")
    session.key("<Tab>")
    session.key("<Esc>")

    assertEquals(null, session.display.matches)
  }

  /**
   * The command *name* completes too, including this host's own.
   *
   * Not new - it is the other half of what the engine's parser decides - but it is the proof that
   * the registry Tab reads is the one this host adds to, rather than the engine's alone.
   */
  @Test
  fun `test a command name completes, including one this host declares`() {
    val session = Session()
    session.type(":actionl")
    session.key("<Tab>")

    assertEquals(":actionlist", session.display.shown)
  }

  // The line itself, which is arithmetic and worth testing as arithmetic.

  @Test
  fun `test the wildmenu line brackets the selection and counts`() {
    assertEquals(
      "2/3  aaa  [bbb]  ccc",
      wildmenuLine(listOf("aaa", "bbb", "ccc"), selected = 1),
    )
  }

  @Test
  fun `test the wildmenu line says how many there are before anything is selected`() {
    assertEquals(
      "3 matches  aaa  bbb  ccc",
      wildmenuLine(listOf("aaa", "bbb", "ccc"), selected = null),
    )
  }

  /**
   * A window that keeps the selection on screen.
   *
   * `:action e` against a real VS Code matches a couple of hundred commands. A line that started at
   * the beginning would show the first three and a truncation, and the one Tab had just applied
   * would not be among them.
   */
  @Test
  fun `test the line is windowed around the selection`() {
    val names = (1..20).map { "command-number-$it" }

    val line = wildmenuLine(names, selected = 11, budget = 40)

    assertTrue("[command-number-12]" in line, "the selected one has to be there: $line")
    assertTrue(line.startsWith("12/20  ... "), "and the count says where it is: $line")
    assertTrue(line.endsWith(" ..."), "with both ends marked as cut: $line")
    assertTrue("command-number-1 " !in line, "the first should be off the end of the window: $line")
  }

  @Test
  fun `test a window at the start is not marked as cut at the start`() {
    val line = wildmenuLine((1..20).map { "command-number-$it" }, selected = 0, budget = 40)

    assertTrue(line.startsWith("1/20  [command-number-1]"), line)
    assertTrue(line.endsWith(" ..."), line)
  }

  @Test
  fun `test an empty list draws nothing`() {
    assertEquals("", wildmenuLine(emptyList(), selected = null))
  }
}
