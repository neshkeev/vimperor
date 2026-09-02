/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.vimscript.model.commands.engineExCommandProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ex commands that exist in IdeaVim and cannot exist here, because of where they are written.
 *
 * This is a blind spot the other sweeps are built to have. `VsCodeUnimplementedTest` types every
 * *registered* ex command and watches for a service that is not built - but registration happens
 * through `@ExCommand` in whichever module declares the command, and IdeaVim declares eight of them
 * in its IntelliJ module rather than in `vim-engine`. From the sweep's point of view those are not
 * holes. They are not anything: nothing registered them, so nothing can fail.
 *
 * `:!` was one of them, and so was `:read`. Neither had a line of IntelliJ in it worth the name -
 * `:!` needed a process and `:read` needed to open a file, and both of those are host questions the
 * engine already asks through an interface. They are in `vim-engine` now.
 *
 * What is left needs something this host genuinely does not have, and each line says what. The
 * assertion is on the whole list, in both directions: moving one out of the IntelliJ module has to
 * come with taking it off this list, and adding a new IntelliJ-only command has to be noticed.
 */
class ExCommandsOnlyInIntelliJTest {

  @Test
  fun `test the ex commands only IdeaVim has are the ones listed here`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so the sources could not be read")

    val registered = engineExCommandProvider.getCommands().keys
    val onlyInIntelliJ = mutableListOf<String>()
    for (path in kotlinFilesUnder(root!! + "/src/main")) {
      for (match in EX_COMMAND.findAll(readText(path))) {
        val command = match.groupValues[1]
        if (command !in registered) onlyInIntelliJ += command
      }
    }

    assertEquals(EXPECTED.trim(), onlyInIntelliJ.sorted().joinToString("\n").trim())
  }

  /**
   * The engine really is where a command has to be for this host to have it.
   *
   * Without this the test above would pass just as well if `registered` were empty or if the regex
   * matched nothing - and both of those are silent. `:!` is the one that moved, so it is the one
   * that proves the comparison works from both sides.
   */
  @Test
  fun `test a command moved into the engine is registered rather than listed`() {
    assertTrue("!" in engineExCommandProvider.getCommands().keys, ":! should be an engine command now")
    assertTrue("r[ead]" in engineExCommandProvider.getCommands().keys, ":read should be an engine command now")
    assertTrue("h[elp]" in engineExCommandProvider.getCommands().keys, ":help should be an engine command now")
    assertTrue("b[uffer]" in engineExCommandProvider.getCommands().keys, ":buffer should be an engine command now")
    assertTrue("!" !in EXPECTED, ":! should not still be listed as IntelliJ-only")
  }

  private companion object {
    val EX_COMMAND = Regex("""@ExCommand\(command\s*=\s*"([^"]*)"""")

    /**
     * Each of these needs something VS Code does not offer, or offers only asynchronously.
     *
     * `:actionlist` lists IntelliJ's actions by id. VS Code's equivalent is `getCommands`, which
     * returns a promise - and this host already prints the ones it uses at activation.
     *
     * `:resize` and `:vertical` size a split. VS Code has no API for the size of an editor group -
     * only commands to grow or shrink one by an unspecified amount.
     *
     * `:buffer`, `:ls`, `:files` and `:buffers` were on this list, with the reason that "VS Code has
     * tabs rather than buffers, and its tab model does not carry the modified/loaded state Vim
     * prints in that table". The first clause is a difference that does not matter and the second
     * was wrong: `Tab.isDirty` and `Tab.isActive` are Vim's `+` and `%`. They are engine commands
     * now. That makes three of these notes that turned out to be about which API had been looked at
     * rather than about VS Code, so a line here is a claim to be checked, not a decision that is
     * already made.
     */
    val EXPECTED = """
      actionl[ist]
      res[ize]
      vert[ical]
    """.trimIndent()
  }
}
