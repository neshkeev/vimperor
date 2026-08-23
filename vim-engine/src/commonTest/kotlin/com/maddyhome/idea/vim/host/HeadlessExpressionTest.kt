/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vimscript expressions evaluated end to end, on both targets, with no editor involved.
 *
 * This reaches further than anything before it: the expression visitor, the builtin function
 * registry - 93 handlers, read from a JSON resource on the JVM and a generated registry on JS - and
 * the `VimInt`/`VimString`/`VimFloat` datatypes, including the float formatting written by hand
 * earlier in this port against a golden table.
 */
class HeadlessExpressionTest {

  /**
   * The builtins are not registered by construction - `registerHandlers()` reads the providers, and
   * on the JVM that is what opens the JSON resource. A host has to call it, which IdeaVim does at
   * plugin startup.
   */
  private fun install(): HeadlessInjector {
    val host = HeadlessInjector()
    injector = host
    injector.functionService.registerHandlers()
    return host
  }

  private fun evaluate(expression: String): VimDataType {
    install()
    val parsed = injector.vimscriptParser.parseExpression(expression)
      ?: throw AssertionError("failed to parse: $expression")
    // Expressions with no editor in them still take one; these never look at it.
    val editor = TestVimEditor("", listOf(TestVimCaret(0)))
    return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext)
  }

  private fun value(expression: String): String = evaluate(expression).toOutputString()

  @Test
  fun `test arithmetic`() {
    assertEquals("3", value("1 + 2"))
    assertEquals("-1", value("1 - 2"))
    assertEquals("6", value("2 * 3"))
    assertEquals("2", value("7 / 3"))
    assertEquals("1", value("7 % 3"))
  }

  @Test
  fun `test string builtins`() {
    assertEquals("ABC", value("toupper('abc')"))
    assertEquals("abc", value("tolower('ABC')"))
    assertEquals("3", value("len('abc')"))
  }

  @Test
  fun `test list builtins`() {
    assertEquals("3", value("len([1, 2, 3])"))
    assertEquals("6", value("max([1, 6, 3])"))
    assertEquals("1", value("min([1, 6, 3])"))
  }

  @Test
  fun `test float formatting goes through the same path on both targets`() {
    // `VimFloat.toOutputString` is the formatter this port implemented by hand; these are rows from
    // its golden table, reached here through the parser and evaluator rather than directly.
    assertEquals("1.5", value("1.5"))
    assertEquals("0.1", value("0.1"))
    assertEquals("3.0", value("1.5 + 1.5"))
  }

  @Test
  fun `test comparison and logic`() {
    assertEquals("1", value("1 < 2"))
    assertEquals("0", value("2 < 1"))
    assertEquals("1", value("1 && 1"))
    assertEquals("0", value("1 && 0"))
  }

  @Test
  fun `test the function registry holds the engine's builtins`() {
    install()
    // `strlen` is deliberately not here: it is registered by the IntelliJ side, and the engine's own
    // list is the 93 functions in `engine_vimscript_functions.json`.
    for (name in listOf("len", "max", "min", "toupper", "tolower", "abs", "empty")) {
      val handler = injector.functionService.getFunctionHandlerOrNull(null, name, CommandLineVimLContext)
      assertTrue(handler != null, "$name is not registered")
    }
    assertTrue(
      injector.functionService.getFunctionHandlerOrNull(null, "strlen", CommandLineVimLContext) == null,
      "strlen is an IntelliJ-side function and should not be in the engine's list",
    )
  }
}

/**
 * Nothing to carry. `ExecutionContext` is how IntelliJ threads its `DataContext` through the engine;
 * a headless host has no such object, and the engine only reaches for it when handing control back
 * to the IDE.
 */
internal object HeadlessExecutionContext : ExecutionContext {
  override val context: Any get() = TODO("headless host has no execution context yet")
}
