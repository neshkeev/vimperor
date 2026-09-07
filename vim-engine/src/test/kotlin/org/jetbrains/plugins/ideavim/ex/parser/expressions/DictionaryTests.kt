/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.parser.expressions

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFloat
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import org.jetbrains.plugins.ideavim.ex.evaluate
import org.jetbrains.plugins.ideavim.ex.parser.ParserTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DictionaryTests : ParserTest() {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
  }

  
  @Test
  fun `empty dictionary test`() {
    val expression = injector.vimscriptParser.parseExpression("{}")!!.evaluate()
    assertIs<VimDictionary>(expression)
    assertTrue(expression.dictionary.isEmpty())
  }

  @Test
  fun `dictionary of simple types test`() {
    val dictString = "{$ZERO_OR_MORE_SPACES" +
      "'a'$ZERO_OR_MORE_SPACES:$ZERO_OR_MORE_SPACES'string expression'$ZERO_OR_MORE_SPACES,$ZERO_OR_MORE_SPACES" +
      "'b'$ZERO_OR_MORE_SPACES:$ZERO_OR_MORE_SPACES[1, 2]$ZERO_OR_MORE_SPACES" +
      "}"
    for (s in getTextWithAllSpacesCombinations(dictString)) {
      val expression = injector.vimscriptParser.parseExpression(s)!!.evaluate()
      assertIs<VimDictionary>(expression)
      assertEquals(VimString("string expression"), expression.dictionary[VimString("a")])
      val list = assertIs<VimList>(expression.dictionary[VimString("b")])
      assertEquals(VimInt(1), list[0])
      assertEquals(VimInt(2), list[1])
    }
  }

  @Test
  fun `dictionary of simple types test 2`() {
    val dictString = "{$ZERO_OR_MORE_SPACES" +
      "'c'$ZERO_OR_MORE_SPACES:$ZERO_OR_MORE_SPACES{'key':'value'},$ZERO_OR_MORE_SPACES" +
      "'d'$ZERO_OR_MORE_SPACES:${ZERO_OR_MORE_SPACES}5$ZERO_OR_MORE_SPACES" +
      "}"
    for (s in getTextWithAllSpacesCombinations(dictString)) {
      val expression = injector.vimscriptParser.parseExpression(s)!!.evaluate()
      assertIs<VimDictionary>(expression)
      val innerDictionary = assertIs<VimDictionary>(expression.dictionary[VimString("c")])
      assertEquals(VimString("value"), innerDictionary.dictionary[VimString("key")])
      assertEquals(VimInt(5), expression.dictionary[VimString("d")])
    }
  }

  @Test
  fun `dictionary of simple types test 3`() {
    val dictString = "{$ZERO_OR_MORE_SPACES" +
      "'e'$ZERO_OR_MORE_SPACES:${ZERO_OR_MORE_SPACES}4.2$ZERO_OR_MORE_SPACES" +
      "}"
    for (s in getTextWithAllSpacesCombinations(dictString)) {
      val expression = injector.vimscriptParser.parseExpression(s)!!.evaluate()
      assertIs<VimDictionary>(expression)
      assertEquals(VimFloat(4.2), expression.dictionary[VimString("e")])
    }
  }

  @Test
  fun `empty literal dictionary test`() {
    val expression = injector.vimscriptParser.parseExpression("#{}")!!.evaluate()
    assertIs<VimDictionary>(expression)
    assertTrue(expression.dictionary.isEmpty())
  }

  @Test
  fun `literal dictionary of simple types test`() {
    val dictString =
      "#{${ZERO_OR_MORE_SPACES}test$ZERO_OR_MORE_SPACES:${ZERO_OR_MORE_SPACES}12$ZERO_OR_MORE_SPACES," +
        "${ZERO_OR_MORE_SPACES}2-1$ZERO_OR_MORE_SPACES:$ZERO_OR_MORE_SPACES'string value'$ZERO_OR_MORE_SPACES}"
    for (s in getTextWithAllSpacesCombinations(dictString)) {
      val expression = injector.vimscriptParser.parseExpression(s)!!.evaluate()
      assertIs<VimDictionary>(expression)
      assertEquals(VimInt(12), expression.dictionary[VimString("test")])
      assertEquals(VimString("string value"), expression.dictionary[VimString("2-1")])
    }
  }

  @Test
  fun `comma at dictionary end test`() {
    val expression = injector.vimscriptParser.parseExpression("{'one': 1,}")!!.evaluate()
    assertIs<VimDictionary>(expression)
    assertEquals(VimInt.ONE, expression.dictionary[VimString("one")])
  }

  @Test
  fun `comma at literal dictionary end test`() {
    val expression = injector.vimscriptParser.parseExpression("#{one: 1,}")!!.evaluate()
    assertIs<VimDictionary>(expression)
    assertEquals(VimInt.ONE, expression.dictionary[VimString("one")])
  }
}
