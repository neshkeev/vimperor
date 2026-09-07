/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.commands.EchoCommand
import com.maddyhome.idea.vim.vimscript.model.commands.LetCommand
import com.maddyhome.idea.vim.vimscript.model.commands.SetCommand
import com.maddyhome.idea.vim.vimscript.model.expressions.BinExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.SimpleExpression
import com.maddyhome.idea.vim.vimscript.model.statements.IfStatement
import com.maddyhome.idea.vim.vimscript.model.statements.loops.ForLoop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Vimscript parsed into the objects the engine executes, on both targets.
 *
 * The corpus differential compares parse *trees*; this checks the layer above it - the five
 * visitors, and the ex-command registry they build `Command` objects through. That layer is where
 * W1's nullable-visitor question actually lands, so trees agreeing is not the same as commands
 * agreeing.
 */
class HeadlessVimscriptTest {

  private fun parse(script: String) = run {
    injector = HeadlessInjector()
    injector.vimscriptParser.parse(script)
  }

  @Test
  fun `test a set command is recognised as one`() {
    val script = parse("set number")
    assertEquals(1, script.units.size)
    assertIs<SetCommand>(script.units.single())
  }

  @Test
  fun `test a let command keeps its expression`() {
    val script = parse("let x = 1 + 2")
    val let = assertIs<LetCommand>(script.units.single())
    val expression = assertIs<BinExpression>(let.expression)
    assertIs<SimpleExpression>(expression.left)
    assertIs<SimpleExpression>(expression.right)
  }

  @Test
  fun `test an if statement keeps its branches`() {
    val script = parse(
      """
      |if 1
      |  echo "yes"
      |else
      |  echo "no"
      |endif
      """.trimMargin()
    )
    val ifStatement = assertIs<IfStatement>(script.units.single())
    assertEquals(2, ifStatement.conditionToBody.size)
    ifStatement.conditionToBody.forEach { (_, body) ->
      assertIs<EchoCommand>(body.single())
    }
  }

  @Test
  fun `test a for loop keeps its body`() {
    val script = parse(
      """
      |for i in [1, 2, 3]
      |  echo i
      |endfor
      """.trimMargin()
    )
    val loop = assertIs<ForLoop>(script.units.single())
    assertIs<EchoCommand>(loop.body.single())
  }

  @Test
  fun `test the ex-command tree resolves names and their abbreviations`() {
    injector = HeadlessInjector()
    val commands = injector.vimscriptParser.exCommands
    // The tree is built from `engineExCommandProvider`, which is the JSON resource on the JVM and
    // the generated registry on JS - so this is the one assertion that both mechanisms produce a
    // usable command table, not merely a populated map.
    assertEquals("substitute", commands.getFullCommandName("s"))
    assertEquals("substitute", commands.getFullCommandName("sub"))
    assertEquals("delete", commands.getFullCommandName("d"))
    assertEquals("global", commands.getFullCommandName("g"))
    assertTrue(commands.getCommand("substitute") != null, "substitute has no instance")
    assertTrue(commands.getCommand("qwertyuiop") == null, "an unknown name resolved to something")
  }
}
