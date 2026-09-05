/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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
 * **The list is empty now.** Every ex command IdeaVim declares is either in `vim-engine`, where
 * both hosts get it, or declared by this host itself. The assertion is still on the whole list and
 * still in both directions - it is what would notice a new IntelliJ-only command arriving, and it
 * is what would notice one being added here without being reachable.
 */
class ExCommandsOnlyInIntelliJTest {

  @Test
  fun `test the ex commands only IdeaVim has are the ones listed here`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so the sources could not be read")

    // Both registries: a command IdeaVim declares in its module is not missing here if this host
    // declares its own, which is what `:actionlist` and the tutor do.
    val registered = engineExCommandProvider.getCommands().keys + VsCodeExCommandProvider.getCommands().keys
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

  /** ...and so does a command this host declares for itself rather than sharing. */
  @Test
  fun `test a command this host declares of its own is not listed either`() {
    assertTrue(
      "actionl[ist]" in VsCodeExCommandProvider.getCommands().keys,
      ":actionlist should be registered by this host's provider",
    )
    assertTrue("actionl" !in EXPECTED, ":actionlist should not still be listed as IntelliJ-only")
  }

  private companion object {
    val EX_COMMAND = Regex("""@ExCommand\(command\s*=\s*"([^"]*)"""")

    /**
     * Each of these needs something VS Code does not offer, or offers only asynchronously.
     *
     * `:actionlist` was on this list, with the reason that VS Code's equivalent - `getCommands` -
     * returns a promise. It does, and the promise is answered once at activation and the answer
     * kept, which is all the command needed. It is this host's own now, declared through
     * `commandProviders` next to the tutor, so it is registered above and not listed here.
     *
     * `:resize` was the last one, with the reason that "VS Code has no API for the size of an
     * editor group - only commands to grow or shrink one by an unspecified amount". Both halves are
     * true and only the first was a reason: `:resize +3` and `:resize -3` are three of those
     * unspecified steps, and `:resize` with no argument is a maximise. `:resize 20` is the one that
     * genuinely does not translate, and it now says so at the command line instead of the whole
     * command being absent.
     *
     * `:vertical` was here beside it, and was not really the same thing: IdeaVim's copy accepted
     * `:vertical resize` and reported `E492` for everything else, where Vim's `:vertical` is a
     * modifier that says which axis the *following* command works on. It is that in `vim-engine`
     * now - it raises a flag and runs the rest of the line - so `:resize` reads the flag, this host
     * gets `:vertical split`, and IdeaVim keeps `:vertical resize` by the same route.
     *
     * `:buffer`, `:ls`, `:files` and `:buffers` were on this list, with the reason that "VS Code has
     * tabs rather than buffers, and its tab model does not carry the modified/loaded state Vim
     * prints in that table". The first clause is a difference that does not matter and the second
     * was wrong: `Tab.isDirty` and `Tab.isActive` are Vim's `+` and `%`. They are engine commands
     * now. That makes five of these notes that turned out to be about which API had been looked at
     * rather than about VS Code - and the fifth emptied the list. A line here was a claim to be
     * checked, never a decision that was already made.
     */
    val EXPECTED = """"""
  }
}
