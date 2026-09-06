/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.parser.expressions

import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.VariableExpression
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import org.jetbrains.plugins.ideavim.ex.evaluate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableExpressionParsingTests {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
  }

  @Test
  fun variableTest() {
    val variable = injector.vimscriptParser.parseExpression("variableName")
    assertTrue(variable is VariableExpression)
    assertTrue(variable.scope == null)
    assertEquals("variableName", variable.name.evaluate().value)
  }

  @Test
  fun variableTest2() {
    val variable = injector.vimscriptParser.parseExpression("t:variableName")
    assertTrue(variable is VariableExpression)
    assertEquals(Scope.TABPAGE_VARIABLE, variable.scope)
    assertEquals("variableName", variable.name.evaluate().value)
  }
}
