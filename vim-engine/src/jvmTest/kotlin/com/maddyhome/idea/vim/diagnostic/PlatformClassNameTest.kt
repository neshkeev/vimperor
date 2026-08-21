/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.diagnostic

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [platformClassName] to `Class.getName()` semantics.
 *
 * Action ids are derived from this - EditorActionHandlerBase takes everything after the last dot -
 * and they appear in users' `<Action>` mappings, so replacing it with `simpleName` would rename
 * actions rather than merely shorten a string. IdeaVim really does have a nested action handler,
 * Matchit's, so this is not hypothetical.
 *
 * The id rule is restated here rather than called, because EditorActionHandlerBase needs an
 * initialised injector to load and this is a plain unit test.
 */
class PlatformClassNameTest {

  class Nested

  private fun idPartOf(className: String) = className.takeLastWhile { it != '.' }

  @Test
  fun `test platformClassName reports the JVM binary name, including the nested-class dollar`() {
    assertEquals(Nested().javaClass.name, platformClassName(Nested()))
    assertTrue(
      platformClassName(Nested()).endsWith("PlatformClassNameTest\u0024Nested"),
      "expected a nested-class name, got " + platformClassName(Nested()),
    )
  }

  @Test
  fun `test the derived id part differs from simpleName for a nested class`() {
    assertEquals("PlatformClassNameTest\u0024Nested", idPartOf(platformClassName(Nested())))
    assertNotEquals(
      Nested::class.simpleName,
      idPartOf(platformClassName(Nested())),
      "if these ever agree, the nested-class case has stopped being covered",
    )
  }

  @Test
  fun `test a top-level class is unaffected either way`() {
    assertEquals("PlatformClassNameTest", idPartOf(platformClassName(this)))
    assertEquals(this::class.simpleName, idPartOf(platformClassName(this)))
  }
}
