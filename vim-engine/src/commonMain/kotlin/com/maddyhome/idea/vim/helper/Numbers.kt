/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * The result of reading an integer off the front of a string: [value] is null when there was no
 * integer there, and [endIndex] is where reading stopped - the same pair `java.text.NumberFormat`
 * reported through a `ParsePosition`.
 */
class ParsedInt(val value: Int?, val endIndex: Int)

/**
 * Reads an optional `-` followed by ASCII digits, starting at [startIndex].
 *
 * This replaces `NumberFormat.getIntegerInstance().parse(text, ParsePosition(startIndex))`, which
 * search-offset parsing used, and it is **deliberately narrower than what that accepted**. The JDK's
 * integer format is locale-aware: it also takes the current locale's grouping separator and its
 * digits, so in an English locale `/pattern/e+1,5` read as an offset of 15. Vim reads these offsets
 * with `atoi`, so it stops at the comma and the offset is 1. Matching Vim is the point of this
 * project, and the old behaviour was not even stable - it changed with the IDE's locale.
 *
 * A leading `+` is rejected, which the JDK also did: its default positive prefix is empty. Callers
 * strip the `+` themselves and pass the index after it, and that must keep working.
 */
fun parseIntPrefix(text: String, startIndex: Int): ParsedInt {
  var index = startIndex
  val negative = index < text.length && text[index] == '-'
  if (negative) index++
  val firstDigit = index
  var value = 0L
  while (index < text.length && text[index] in '0'..'9') {
    // Saturating, rather than wrapping: an offset this large is meaningless either way, and it
    // keeps `endIndex` honest by consuming the whole run of digits.
    if (value < Int.MAX_VALUE) value = value * 10 + (text[index] - '0')
    index++
  }
  if (index == firstDigit) return ParsedInt(null, startIndex)
  val magnitude = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  return ParsedInt(if (negative) -magnitude else magnitude, index)
}

/**
 * Reads [text] as an unsigned integer in [radix], keeping only the low 64 bits.
 *
 * `<C-A>` on a hex or octal number is 64-bit unsigned arithmetic that wraps: `0x0000` decremented
 * is `0xffffffffffffffff`. That was written with `BigInteger` and an explicit "if it went negative,
 * add 2^64" correction; `ULong` wraps on its own, so the correction disappears.
 *
 * Digits beyond 64 bits are dropped rather than throwing, which is what "keep the low 64 bits"
 * means and what the old code's modular correction implied. Wrapping *upward* is where the two
 * differ: `BigInteger` let `0xffffffffffffffff` incremented grow a seventeenth digit, and this
 * wraps it to zero, which is what Vim does.
 */
fun parseUnsignedWrapping(text: String, radix: Int): ULong {
  var value = 0uL
  for (c in text) {
    val digit = when {
      c in '0'..'9' -> c - '0'
      c in 'a'..'z' -> c - 'a' + 10
      c in 'A'..'Z' -> c - 'A' + 10
      else -> throw IllegalArgumentException("not a digit in radix $radix: '$c'")
    }
    if (digit >= radix) throw IllegalArgumentException("digit '$c' is out of range for radix $radix")
    value = value * radix.toULong() + digit.toULong()
  }
  return value
}

private fun compareMagnitudes(a: String, b: String): Int =
  if (a.length != b.length) a.length - b.length else a.compareTo(b)

private fun addMagnitudes(a: String, b: String): String {
  val result = StringBuilder()
  var carry = 0
  var i = a.length - 1
  var j = b.length - 1
  while (i >= 0 || j >= 0 || carry > 0) {
    val sum = carry + (if (i >= 0) a[i--] - '0' else 0) + (if (j >= 0) b[j--] - '0' else 0)
    result.append('0' + sum % 10)
    carry = sum / 10
  }
  return result.reverse().toString()
}

/** [a] must not be smaller than [b]. */
private fun subtractMagnitudes(a: String, b: String): String {
  val result = StringBuilder()
  var borrow = 0
  var i = a.length - 1
  var j = b.length - 1
  while (i >= 0) {
    var digit = (a[i--] - '0') - borrow - (if (j >= 0) b[j--] - '0' else 0)
    borrow = if (digit < 0) 1 else 0
    if (digit < 0) digit += 10
    result.append('0' + digit)
  }
  return result.reverse().toString().trimStart('0').ifEmpty { "0" }
}

/**
 * Adds [delta] to a decimal integer held as a string, in the form `BigInteger` produced.
 *
 * Arbitrary precision, deliberately. Vim's `<C-A>` is 64-bit and would wrap, but this has always
 * incremented a number of any length correctly, and someone incrementing a long identifier would
 * lose data if it started wrapping. Leading zeros are dropped, `-0` normalises to `0`, and the
 * caller re-pads to the original width - all as before.
 */
fun addToDecimalString(text: String, delta: Int): String {
  val textNegative = text.startsWith("-")
  val textMagnitude = text.substring(if (textNegative) 1 else 0).trimStart('0').ifEmpty { "0" }
  // Via Long: `abs` of Int.MIN_VALUE overflows an Int.
  val deltaLong = delta.toLong()
  val deltaNegative = deltaLong < 0
  val deltaMagnitude = (if (deltaNegative) -deltaLong else deltaLong).toString()

  val (negative, magnitude) = if (textNegative == deltaNegative) {
    textNegative to addMagnitudes(textMagnitude, deltaMagnitude)
  } else {
    val comparison = compareMagnitudes(textMagnitude, deltaMagnitude)
    when {
      comparison >= 0 -> textNegative to subtractMagnitudes(textMagnitude, deltaMagnitude)
      else -> deltaNegative to subtractMagnitudes(deltaMagnitude, textMagnitude)
    }
  }
  return if (negative && magnitude != "0") "-$magnitude" else magnitude
}
