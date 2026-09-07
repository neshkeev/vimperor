/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.parser.commands

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.vimscript.model.commands.GoToLineCommand
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Note that this just tests that something like `:4` or `:14` is parsed as an instance of GoToLineCommand.
// There are other tests elsewhere for the implementation of GoToLineCommand.
class GoToLineCommandParserTest  {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
  }

  @Test
  fun `digit as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("4")
    assertTrue(c is GoToLineCommand)
  }

  @Test
  fun `number as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("14")
    assertTrue(c is GoToLineCommand)
  }

  @Test
  fun `range as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("1,4")
    assertTrue(c is GoToLineCommand)
  }

  @Test
  fun `mark range as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("'a")
    assertTrue(c is GoToLineCommand)
  }

  @Test
  fun `last line range as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("$")
    assertTrue(c is GoToLineCommand)
  }

  @Test
  fun `offset range as go to line command`() {
    val c = injector.vimscriptParser.parseCommand("+2")
    assertTrue(c is GoToLineCommand)
  }
}
