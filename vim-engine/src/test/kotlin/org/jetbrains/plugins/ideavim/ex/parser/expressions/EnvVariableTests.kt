/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.parser.expressions

import com.maddyhome.idea.vim.vimscript.model.expressions.EnvVariableExpression
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessInjector
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvVariableTests {

  @BeforeTest
  fun installHeadlessInjector() {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
  }

  @Test
  fun `environment variable test`() {
    val ex = injector.vimscriptParser.parseExpression("\$JAVA_HOME")
    assertTrue(ex is EnvVariableExpression)
    assertEquals("JAVA_HOME", ex.variableName)
  }
}
