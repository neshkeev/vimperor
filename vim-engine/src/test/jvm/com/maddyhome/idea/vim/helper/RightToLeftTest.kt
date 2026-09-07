/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the embedded right-to-left table to `java.lang.Character.getDirectionality`.
 *
 * The table is shared by both targets rather than being expect/actual, so this is the only place it
 * can be checked against an authority. It walks every codepoint - the table is a binary search over
 * 134 ranges, so exhaustive is cheap and a sampled test would miss a boundary, which is exactly the
 * kind of error a hand-edited range table acquires.
 */
class RightToLeftTest {

  private fun jdkSaysRightToLeft(codepoint: Int): Boolean {
    val d = Character.getDirectionality(codepoint)
    return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
      d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
      d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
      d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
  }

  @Test
  fun `test the table agrees with the JDK for every codepoint`() {
    var checked = 0
    var rightToLeft = 0
    for (cp in 0..0x10FFFF) {
      val expected = jdkSaysRightToLeft(cp)
      if (expected) rightToLeft++
      assertEquals(expected, isRightToLeft(cp), "codepoint U+%04X".format(cp))
      checked++
    }
    assertEquals(0x110000, checked)
    assertTrue(rightToLeft > 1000, "expected a substantial RTL set, found " + rightToLeft)
  }

  @Test
  fun `test known right-to-left and left-to-right characters`() {
    assertTrue(isRightToLeft(0x05D0), "Hebrew alef")
    assertTrue(isRightToLeft(0x0627), "Arabic alef")
    assertTrue(isRightToLeft(0x07CA), "NKo")
    kotlin.test.assertFalse(isRightToLeft('a'.code))
    kotlin.test.assertFalse(isRightToLeft(0x4E2D), "CJK is neutral, not RTL")
    kotlin.test.assertFalse(isRightToLeft(0x10FFFF), "unassigned")
  }
}
