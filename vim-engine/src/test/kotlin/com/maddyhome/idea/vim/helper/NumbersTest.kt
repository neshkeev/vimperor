/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The same arithmetic on both targets. `NumbersDifferentialTest` checks it against `BigInteger`,
 * which only the JVM has; this checks that JS agrees with the JVM on the cases that matter.
 */
class NumbersTest {

  @Test
  fun `test decimal addition carries and borrows`() {
    assertEquals("1000", addToDecimalString("999", 1))
    assertEquals("999", addToDecimalString("1000", -1))
    assertEquals("0", addToDecimalString("-1", 1))
    assertEquals("-1", addToDecimalString("0", -1))
    assertEquals("100000000000000000000000001", addToDecimalString("100000000000000000000000000", 1))
    assertEquals("-100000000000000000000000001", addToDecimalString("-100000000000000000000000000", -1))
  }

  @Test
  fun `test decimal addition normalises leading zeros and minus zero`() {
    assertEquals("8", addToDecimalString("007", 1))
    assertEquals("0", addToDecimalString("-0", 0))
    assertEquals("-8", addToDecimalString("-007", -1))
  }

  @Test
  fun `test decimal addition takes the extreme deltas`() {
    assertEquals("-2147483648", addToDecimalString("0", Int.MIN_VALUE))
    assertEquals("2147483647", addToDecimalString("0", Int.MAX_VALUE))
  }

  @Test
  fun `test unsigned parsing wraps at 64 bits in both directions`() {
    assertEquals("ffffffffffffffff", (parseUnsignedWrapping("0", 16) - 1uL).toString(16))
    assertEquals("0", (parseUnsignedWrapping("ffffffffffffffff", 16) + 1uL).toString(16))
    assertEquals("1777777777777777777777", (parseUnsignedWrapping("0", 8) - 1uL).toString(8))
    assertEquals("ff", parseUnsignedWrapping("00ff", 16).toString(16))
  }

  @Test
  fun `test integer prefix parsing stops where the digits stop`() {
    parseIntPrefix("3;/foo", 0).let {
      assertEquals(3, it.value)
      assertEquals(1, it.endIndex)
    }
    parseIntPrefix("e-12x", 1).let {
      assertEquals(-12, it.value)
      assertEquals(4, it.endIndex)
    }
    // A leading '+' is not accepted, as `NumberFormat` did not accept one either.
    parseIntPrefix("+3", 0).let {
      assertNull(it.value)
      assertEquals(0, it.endIndex)
    }
    // Nothing to read leaves the index where it started, so the caller's `;` search is unaffected.
    parseIntPrefix("e+", 2).let {
      assertNull(it.value)
      assertEquals(2, it.endIndex)
    }
  }
}
