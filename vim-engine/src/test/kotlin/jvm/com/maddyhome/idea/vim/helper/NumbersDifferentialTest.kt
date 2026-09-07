/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Checks the hand-written arithmetic in `Numbers.kt` against the `BigInteger` it replaced.
 *
 * Schoolbook addition with a borrow is exactly the kind of code that looks right and is wrong at
 * one carry, so it is compared against the JDK rather than against a table someone typed out. This
 * lives in `jvmTest` because `BigInteger` is the thing being compared to; the behaviour itself is
 * common, and `NumbersTest` pins a handful of cases on both targets.
 */
class NumbersDifferentialTest {

  private val random = Random(20260822)

  private fun randomDecimal(maxDigits: Int): String {
    val digits = 1 + random.nextInt(maxDigits)
    val text = buildString {
      // A leading zero is allowed and normalised away, so generate them.
      repeat(digits) { append('0' + random.nextInt(10)) }
    }
    return if (random.nextBoolean()) "-$text" else text
  }

  @Test
  fun `test addToDecimalString matches BigInteger`() {
    repeat(20000) {
      val text = randomDecimal(maxDigits = 25)
      val delta = when (random.nextInt(4)) {
        0 -> random.nextInt(-10, 10)
        1 -> Int.MIN_VALUE
        2 -> Int.MAX_VALUE
        else -> random.nextInt()
      }
      assertEquals(
        BigInteger(text).add(BigInteger.valueOf(delta.toLong())).toString(),
        addToDecimalString(text, delta),
        "adding $delta to $text",
      )
    }
  }

  @Test
  fun `test addToDecimalString handles the boundaries`() {
    val cases = listOf(
      "0" to 0, "0" to -1, "-0" to 1, "000" to 5, "-000" to -5,
      "9" to 1, "99" to 1, "100" to -1, "1000000000000000000000" to 1,
      "-1" to 1, "-1" to -1, "1" to -1, "-9223372036854775808" to Int.MIN_VALUE,
    )
    for ((text, delta) in cases) {
      assertEquals(
        BigInteger(text).add(BigInteger.valueOf(delta.toLong())).toString(),
        addToDecimalString(text, delta),
        "adding $delta to $text",
      )
    }
  }

  @Test
  fun `test parseUnsignedWrapping matches BigInteger for values that fit`() {
    val mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    for (radix in listOf(8, 16)) {
      repeat(10000) {
        val digits = 1 + random.nextInt(if (radix == 16) 16 else 21)
        val text = buildString {
          repeat(digits) { append(BigInteger.valueOf(random.nextInt(radix).toLong()).toString(radix)) }
        }
        assertEquals(
          BigInteger(text, radix).and(mask).toString(radix),
          parseUnsignedWrapping(text, radix).toString(radix),
          "parsing $text in radix $radix",
        )
      }
    }
  }

  @Test
  fun `test parseUnsignedWrapping wraps where BigInteger grew`() {
    // The one deliberate difference: BigInteger let this become a seventeenth digit.
    assertEquals(0uL, parseUnsignedWrapping("ffffffffffffffff", 16) + 1uL)
    assertEquals("ffffffffffffffff", (parseUnsignedWrapping("0", 16) - 1uL).toString(16))
  }
}
