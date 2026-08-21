/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.vimscript.model.datatypes

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract for [formatVimFloat], as a table.
 *
 * These are what `:echo` prints, so they are user-visible and must not drift. The values were
 * produced by the `java.text.DecimalFormat` implementation this replaced, and any other host's
 * actual has to reproduce them exactly - half-even rounding, the retained sign on negative zero,
 * the one-fraction-digit minimum, and the six-digit maximum included.
 *
 * The interesting rows are the ones that are easy to get wrong: 1.2345665 and 1.0000005 pin
 * half-even against the exact binary value rather than the shortest decimal form, and 999999.9999999
 * pins the fact that the scientific-notation branch is chosen before rounding, so the result may
 * legitimately carry seven integer digits.
 */
class FloatFormatTest {

  private fun format(value: Double): String {
    val scientific = abs(value) >= 1e6 || (abs(value) < 1e-3 && value != 0.0)
    return formatVimFloat(value, scientific)
  }

  private val golden = listOf(
      0.0 to "0.0",
      -0.0 to "-0.0",
      1.0 to "1.0",
      -1.0 to "-1.0",
      100.0 to "100.0",
      -100.0 to "-100.0",
      0.5 to "0.5",
      1.5 to "1.5",
      2.5 to "2.5",
      3.5 to "3.5",
      0.1 to "0.1",
      0.25 to "0.25",
      0.3333333333333333 to "0.333333",
      0.6666666666666666 to "0.666667",
      0.14285714285714285 to "0.142857",
      3.142857142857143 to "3.142857",
      3.14159265358979 to "3.141593",
      2.718281828459045 to "2.718282",
      1.2345675 to "1.234568",
      1.2345665 to "1.234566",
      1.0000005 to "1.000001",
      1.0000004 to "1.0",
      5.0E-7 to "5.0e-7",
      123456.789 to "123456.789",
      999999.9 to "999999.9",
      999999.99 to "999999.99",
      999999.9999999 to "1000000.0",
      999999.5 to "999999.5",
      1000000.0 to "1.0e6",
      -1000000.0 to "-1.0e6",
      1000000.5 to "1.0e6",
      0.001 to "0.001",
      -0.001 to "-0.001",
      9.99E-4 to "9.99e-4",
      1.0E-4 to "1.0e-4",
      1.5E-7 to "1.5e-7",
      1.0E-15 to "1.0e-15",
      1.0E15 to "1.0e15",
      1.0E100 to "1.0e100",
      1.0E-100 to "1.0e-100",
      1.7976931348623157E308 to "1.797693e308",
      4.9E-324 to "4.9e-324",
      12345.654321 to "12345.654321",
      -12345.654321 to "-12345.654321",
      0.0012345678 to "0.001235",
      6.02214076E23 to "6.022141e23",
  )

  @Test
  fun `test formatVimFloat matches the golden table`() {
    for ((value, expected) in golden) {
      assertEquals(expected, format(value), "formatting " + value)
    }
  }

  @Test
  fun `test the table actually covers the cases it claims to`() {
    val outputs = golden.map { it.second }
    assertEquals("-0.0", format(-0.0), "negative zero must keep its sign")
    assertEquals("1000000.0", format(999999.9999999), "branch is chosen before rounding")
    assertTrue(outputs.any { it.contains("e") }, "table must cover scientific notation")
    assertTrue(outputs.any { !it.contains("e") }, "table must cover plain notation")
    assertTrue(golden.any { it.first < 0 }, "table must cover negatives")
    // Every output carries at least one fraction digit or an exponent.
    for (o in outputs) {
      assertTrue(o.contains('.'), "expected a fraction digit in " + o)
    }
  }
}
