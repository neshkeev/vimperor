/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.implementation.commands

import com.maddyhome.idea.vim.api.VimExternalOpener
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.commands.HelpCommand
import org.jetbrains.plugins.ideavim.mock.MockTestCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpCommandTest : MockTestCase() {
  @Test
  fun `command parsing`() {
    val command = injector.vimscriptParser.parseCommand("help :help")
    assertTrue(command is HelpCommand)
    assertEquals(":help", command.argument)
  }

  @Test
  fun `test bare help opens the documentation root`() {
    val handler = mockService(VimExternalOpener::class.java)
    configureByText("lorem ipsum")

    typeText(commandToKeys("help"))

    verify(handler).open(eq("http://vimdoc.sourceforge.net/htmldoc/"), eq(null))
  }

  /**
   * A topic becomes a search, form encoded.
   *
   * This used to be `URLEncoder.encode`, and the encoding moved into vim-engine with the command -
   * so it is worth asserting rather than assuming. Form encoding, not plain percent encoding: a
   * space becomes `+`, and `-`, `_`, `.` and `*` are left alone.
   */
  @Test
  fun `test a help topic is form encoded into the search query`() {
    val handler = mockService(VimExternalOpener::class.java)
    configureByText("lorem ipsum")

    typeText(commandToKeys("help i_CTRL-W"))

    verify(handler).open(
      eq("http://vimdoc.sourceforge.net/search.php?docs=help&search=i_CTRL-W"),
      eq(null),
    )
  }

  @Test
  fun `test a topic with a space and a percent is escaped`() {
    val handler = mockService(VimExternalOpener::class.java)
    configureByText("lorem ipsum")

    typeText(commandToKeys("help c_% two"))

    verify(handler).open(
      eq("http://vimdoc.sourceforge.net/search.php?docs=help&search=c_%25+two"),
      eq(null),
    )
  }
}