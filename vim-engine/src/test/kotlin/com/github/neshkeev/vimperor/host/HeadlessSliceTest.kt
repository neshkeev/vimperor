/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Taking part of a list or a string: `expr[a : b]` and `slice()`.
 *
 * Vim spells the same operation two ways and they disagree about one index. The sublist expression's
 * end is **inclusive**, so its default is `-1` - the last element - while `slice()`'s end is
 * **exclusive**, so `slice(l, 0, 0)` is the empty list. One pair of functions served both, and the
 * cost was that `endExclusive <= 0` meant "count from the end": an exclusive zero was read as the
 * whole collection rather than as none of it.
 *
 * The fix moves the resolving of a negative index out to the two callers, which are the only places
 * that know which of Vim's two conventions is being used. An exclusive index of zero can then only
 * ever mean empty. Ported from IdeaVim.
 *
 * Both conventions are tested here on purpose. The bug was in the shared half, so a test of either
 * caller alone would have left the other free to be fixed into being wrong.
 */
class HeadlessSliceTest {

  private fun evaluate(expression: String): String {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    val parsed = injector.vimscriptParser.parseExpression(expression)
      ?: throw AssertionError("failed to parse: $expression")
    val editor = TestVimEditor("", listOf(TestVimCaret(0)))
    return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
  }

  // ---- slice(), whose end index is exclusive ----------------------------------------------------

  @Test
  fun `test slice to an end of zero is empty, not everything`() {
    assertEquals("[]", evaluate("slice([1, 2, 3], 0, 0)"))
    assertEquals("", evaluate("slice('abcdef', 0, 0)"))
  }

  @Test
  fun `test slice with no end runs to the end of the collection`() {
    assertEquals("[2, 3]", evaluate("slice([1, 2, 3], 1)"))
    assertEquals("cdef", evaluate("slice('abcdef', 2)"))
  }

  @Test
  fun `test slice stops one short of its end index`() {
    assertEquals("[1, 2]", evaluate("slice([1, 2, 3], 0, 2)"))
    assertEquals("abc", evaluate("slice('abcdef', 0, 3)"))
  }

  @Test
  fun `test a negative end in slice counts back from the end`() {
    assertEquals("[1, 2]", evaluate("slice([1, 2, 3], 0, -1)"))
    assertEquals("abcde", evaluate("slice('abcdef', 0, -1)"))
  }

  @Test
  fun `test a negative start in slice counts back from the end`() {
    assertEquals("[2, 3]", evaluate("slice([1, 2, 3], -2)"))
    assertEquals("ef", evaluate("slice('abcdef', -2)"))
  }

  // ---- expr[a : b], whose end index is inclusive ------------------------------------------------

  @Test
  fun `test a sublist to minus one is the whole collection`() {
    assertEquals("[1, 2, 3]", evaluate("[1, 2, 3][0:-1]"))
    assertEquals("abcdef", evaluate("'abcdef'[0:-1]"))
  }

  @Test
  fun `test a sublist to zero is the first element`() {
    assertEquals("[1]", evaluate("[1, 2, 3][0:0]"))
    assertEquals("a", evaluate("'abcdef'[0:0]"))
  }

  @Test
  fun `test a sublist includes its end index`() {
    assertEquals("[1, 2]", evaluate("[1, 2, 3][0:1]"))
    assertEquals("abc", evaluate("'abcdef'[0:2]"))
  }

  @Test
  fun `test a sublist with an open end runs to the end of the collection`() {
    assertEquals("[2, 3]", evaluate("[1, 2, 3][1:]"))
    assertEquals("ef", evaluate("'abcdef'[-2:]"))
  }

  @Test
  fun `test an end index past the collection is clamped rather than refused`() {
    assertEquals("[1, 2, 3]", evaluate("[1, 2, 3][0:99]"))
    assertEquals("[]", evaluate("slice([1, 2, 3], 2, 1)"))
  }
}
