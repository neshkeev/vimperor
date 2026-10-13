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
import com.maddyhome.idea.vim.help.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:help`, which answers out of Vimperor's own command list.
 *
 * It used to open Vim's documentation in a browser, because "there is no help file to open in an
 * IDE". That stopped being true when the commands learnt to describe themselves: every
 * `@CommandOrMotion` and `@ExCommand` carries a line, those lines reach runtime through the
 * generated registries, and [HelpText] lays them out in the format of Vim's own help files. Ported
 * from IdeaVim, which made the same trade - Vim's prose is richer, and "does Vimperor have this?" is
 * the question a user of *this* extension actually asks.
 *
 * **Rendered rather than generated into a file**, which is this fork's one divergence. IdeaVim writes
 * `help/ideavim.txt` with a Gradle task, commits it and has a workflow to keep it fresh. `commonMain`
 * compiles for JS, where there is no classpath to read a resource from, so there is nothing to commit
 * and nothing to keep in step: the help cannot drift from the commands because it *is* the commands.
 */
class HelpTest {

  private class Session(text: String = "one two three") {
    val fake = FakeEditor(text)

    /** What `:help` was given to put in the buffer, and the buffer it went into. */
    var openedWith: String? = null
    var window: FakeEditor? = null

    val host = VimHost(
      openVirtualBuffer = { content, onOpen ->
        openedWith = content
        val opened = FakeEditor(content, path = "/test/help")
        window = opened
        onOpen(opened.document)
      },
    ).also { it.start() }

    init {
      HelpText.resetForTest()
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  // ---- the rendered file -------------------------------------------------------------------------

  @Test
  fun `test the help names its sections and links to them`() {
    Session()

    val text = HelpText.text

    assertTrue(text.startsWith("*vimperor.txt*"), "the file names itself, as Vim's help files do")
    for (tag in listOf("vimperor-insert", "vimperor-normal", "vimperor-visual", "vimperor-cmdline", "vimperor-ex")) {
      assertTrue(text.contains("|$tag|"), "the contents should link to $tag")
      assertTrue(text.contains("*$tag*"), "and the section should be tagged $tag")
    }
  }

  @Test
  fun `test a command is listed under the keys and the description it declares`() {
    Session()

    val text = HelpText.text

    assertTrue(text.contains("*x*"), "`x` should be tagged. Got ${text.length} characters")
    // The descriptions are wrapped to 78 columns, so the words are matched against the file with its
    // line breaks and indentation collapsed - which is what a reader sees and a `contains` does not.
    val flowed = text.replace(Regex("\\s+"), " ")
    assertTrue(
      flowed.contains("Delete [count] characters under and after the cursor into register [x]."),
      "and described in Vim's own words",
    )
  }

  /** `<C-W>` is `CTRL-W` in a help tag, and a mode has a prefix. Vim's naming, not this host's. */
  @Test
  fun `test the tags are named the way Vim names them`() {
    Session()

    val text = HelpText.text

    assertTrue(text.contains("*i_CTRL-W*"), "an Insert mode control key")
    assertTrue(text.contains("*v_"), "a Visual mode command")
    assertTrue(text.contains("*:s*"), "an Ex command, under its abbreviation")
    assertTrue(text.contains("*:substitute*"), "and under its full name")
  }

  /**
   * Nothing may be added to Vimperor without saying what it does.
   *
   * The reason to assert it rather than trust it: the help is the only place a missing description
   * shows, and it shows as an absence, which is exactly the kind of hole nobody notices. A new
   * command with no description fails here instead.
   */
  @Test
  fun `test every registered command describes itself`() {
    Session()

    val undescribed = injector.vimscriptParser.exCommands.allCommands()
      .filter { it.instance.description.isEmpty() }
      .map { ":" + it.fullCommand }

    assertEquals(emptyList(), undescribed, "these ex commands are missing a description")
  }

  // ---- what `:help` does -------------------------------------------------------------------------

  @Test
  fun `test help opens the help buffer`() {
    val session = Session()

    session.type(":help")
    session.key("<CR>")

    assertTrue(
      session.openedWith?.startsWith("*vimperor.txt*") == true,
      "`:help` should have opened the help. Opened: ${session.openedWith?.take(40)}",
    )
  }

  @Test
  fun `test help with a subject lands on its tag`() {
    Session()

    val line = HelpText.findTag("dd")

    assertTrue(line != null && line > 0, "`dd` should be findable")
    assertTrue(
      HelpText.text.lines()[line!!].contains("*d*") || HelpText.text.lines()[line].contains("*dd*"),
      "a doubled operator leads to the operator, which is where Vimperor implements it",
    )
  }

  @Test
  fun `test a subject can be written in caret notation`() {
    Session()

    assertEquals(
      HelpText.findTag("i_<C-W>"),
      HelpText.findTag("i_^W"),
      "`^W` and `<C-W>` are the same key, so they are the same subject",
    )
  }

  /**
   * `:help substitute` finds `:substitute`, because nothing else is called that. `:help s` does not -
   * `s` is a Normal mode command of its own, and an exact match wins, which is Vim's ranking too.
   */
  @Test
  fun `test an ex command is found without its colon when no key is called that`() {
    Session()

    assertEquals(HelpText.findTag(":substitute"), HelpText.findTag("substitute"))
    assertTrue(
      HelpText.findTag("s") != HelpText.findTag(":s"),
      "`s` is the Normal mode command, not the Ex one",
    )
  }

  @Test
  fun `test an unknown subject is E149`() {
    val session = Session()

    session.type(":help nosuchsubjectatall")
    session.key("<CR>")

    assertEquals(null, session.openedWith, "nothing should have opened")
  }
}
