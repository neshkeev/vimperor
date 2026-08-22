/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.services

import org.junit.jupiter.api.Test
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The variable service checked a map's key type with `String::class.createType()`, which needs
 * `kotlin.reflect.full`. `typeOf<String>()` is a compiler intrinsic and available everywhere, but
 * only if the two are actually equal - including that neither is nullable, which is the whole point
 * of the check.
 */
class StringKTypeEquivalenceTest {

  @Test
  fun `test typeOf String equals the reflected type`() {
    assertEquals(String::class.createType(), typeOf<String>())
  }

  @Test
  fun `test neither matches a nullable String`() {
    assertNotEquals(typeOf<String?>(), typeOf<String>())
    assertNotEquals(String::class.createType(nullable = true), typeOf<String>())
  }
}
