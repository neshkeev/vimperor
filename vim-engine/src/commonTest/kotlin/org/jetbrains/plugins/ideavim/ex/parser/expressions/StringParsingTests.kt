/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.parser.expressions

import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import org.jetbrains.plugins.ideavim.ex.evaluate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StringParsingTests {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
  }

  @Test
  fun `quoted string`() {
    assertEquals(
      VimString("oh, hi Mark"),
      injector.vimscriptParser.parseExpression("\"oh, hi Mark\"")!!.evaluate(),
    )
  }

  @Test
  fun `single quoted string`() {
    assertEquals(
      VimString("oh, hi Mark"),
      injector.vimscriptParser.parseExpression("'oh, hi Mark'")!!.evaluate(),
    )
  }

  @Test
  fun `escaped backslash in quoted string`() {
    assertEquals(
      VimString("oh, \\hi Mark"),
      injector.vimscriptParser.parseExpression("\"oh, \\\\hi Mark\"")!!.evaluate(),
    )
  }

  @Test
  fun `escaped quote quoted string`() {
    assertEquals(
      VimString("oh, hi \"Mark\""),
      injector.vimscriptParser.parseExpression("\"oh, hi \\\"Mark\\\"\"")!!.evaluate(),
    )
  }

  @Test
  fun `backslashes in single quoted string`() {
    assertEquals(
      VimString("oh, hi \\\\Mark\\"),
      injector.vimscriptParser.parseExpression("'oh, hi \\\\Mark\\'")!!.evaluate(),
    )
  }

  @Test
  fun `escaped single quote in single quoted string`() {
    assertEquals(
      VimString("oh, hi 'Mark'"),
      injector.vimscriptParser.parseExpression("'oh, hi ''Mark'''")!!.evaluate(),
    )
  }

  @Test
  fun `single quoted string inside a double quoted string`() {
    assertEquals(
      VimString(" :echo \"no mapping for 45\"<CR>"),
      injector.vimscriptParser.parseExpression(
        """
         ' :echo "no mapping for ' . 45 . '"<CR>'
        """.trimIndent(),
      )!!.evaluate(),
    )
  }
}
