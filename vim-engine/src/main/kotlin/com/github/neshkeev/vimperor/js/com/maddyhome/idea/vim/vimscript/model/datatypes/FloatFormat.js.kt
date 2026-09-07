/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.datatypes

/*
 * Reproduces `DecimalFormat("0.0#####")` and `DecimalFormat("0.0#####E0")`.
 *
 * Two things have to be right, and both were found by the golden table rather than by reading the
 * JDK.
 *
 * **Half-even, not half-up.** `toFixed` rounds half-up, and ties are real: k/128 lands on a
 * seven-digit decimal ending in 5, so 0.0078125 must be 0.007812 and not 0.007813.
 *
 * **The digits come from the shortest round-tripping representation, not the exact value.**
 * DecimalFormat formats the decimal that identifies the double, not the double's full binary
 * expansion. The two differ whenever a double carries fewer than six significant digits - every
 * subnormal - where the exact expansion of 1e-320 begins 9.99988... and the shortest is 1e-320.
 * `toExponential()` with no argument is exactly that shortest form.
 */

private const val MAX_FRACTION_DIGITS = 6
private const val GUARD_DIGITS = 20

/** Rounds a non-negative digit string, given as integer and fraction parts, half-even. */
private fun roundHalfEven(intPart: String, fracPart: String, trueValueVsDigits: Int): Pair<String, String> {
  if (fracPart.length <= MAX_FRACTION_DIGITS) return intPart to fracPart
  val kept = fracPart.substring(0, MAX_FRACTION_DIGITS)
  val rest = fracPart.substring(MAX_FRACTION_DIGITS)
  val first = rest[0]
  val restNonZero = rest.drop(1).any { it != '0' }
  val lastKept = if (kept.isEmpty()) intPart.last() else kept.last()
  val roundUp = when {
    first > '5' -> true
    first < '5' -> false
    restNonZero -> true                       // strictly above the tie
    // A trailing 5 is only a *tie* when the digits are the whole value. Where they are a rounded
    // stand-in the true value sits to one side of the midpoint and that side decides:
    // 1.0000005 is really 1.00000050000000006989 and rounds up, while 1.2345665 is really
    // 1.2345664999999999 and rounds down. Only an exact midpoint gets half-even.
    trueValueVsDigits > 0 -> true
    trueValueVsDigits < 0 -> false
    else -> (lastKept - '0') % 2 == 1         // exactly on the tie: round to even
  }
  if (!roundUp) return intPart to kept
  val digits = (intPart + kept).toCharArray()
  var i = digits.size - 1
  var carry = true
  while (carry && i >= 0) {
    if (digits[i] == '9') {
      digits[i] = '0'
      i--
    } else {
      digits[i] = digits[i] + 1
      carry = false
    }
  }
  val joined = (if (carry) "1" else "") + digits.concatToString()
  val intLen = joined.length - kept.length
  return joined.substring(0, intLen) to joined.substring(intLen)
}

/** Strips trailing zeros but keeps at least one fraction digit, as `0.0#####` does. */
private fun trimFraction(fraction: String): String {
  var end = fraction.length
  while (end > 1 && fraction[end - 1] == '0') end--
  return fraction.substring(0, end).ifEmpty { "0" }
}

/** The shortest decimal that identifies [magnitude], as significant digits plus a base-10 exponent. */
/**
 * [digits] and [exponent] are the shortest decimal identifying the value; [trueValueVsDigits] is
 * the sign of (true value - that decimal), so 0 means the digits are the value exactly.
 */
private class Decimal(val digits: String, val exponent: Int, val trueValueVsDigits: Int)

/**
 * The shortest decimal that identifies [magnitude], and whether it *is* the value or a stand-in.
 *
 * `DecimalFormat` rounds the shortest round-tripping decimal, not the double's full expansion, and
 * it knows whether that decimal is exact. Both halves are needed: the digits decide what is printed,
 * the flag decides what happens at a trailing 5.
 */
private fun shortestDecimal(magnitude: Double): Decimal {
  val text = magnitude.asDynamic().toExponential() as String
  val eIndex = text.indexOf('e')
  val exponent = text.substring(eIndex + 1).toInt()
  val digits = text.substring(0, eIndex).replace(".", "")

  // Compare the shortest digits against a high-precision expansion of the same value. Both are
  // normalised to the same exponent, so padding with zeros makes them directly comparable.
  val precise = (magnitude.asDynamic().toExponential(GUARD_DIGITS) as String)
    .substringBefore('e').replace(".", "")
  val padded = digits.padEnd(precise.length, '0')
  return Decimal(digits, exponent, precise.compareTo(padded))
}

actual fun formatVimFloat(value: Double, scientific: Boolean): String {
  val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)   // catches -0.0
  val magnitude = if (negative) -value else value
  val sign = if (negative) "-" else ""
  val decimal = shortestDecimal(magnitude)
  val digits = decimal.digits
  val exponent = decimal.exponent

  if (scientific) {
    var intPart = digits.substring(0, 1)
    var fracPart = digits.substring(1)
    var adjusted = exponent
    val rounded = roundHalfEven(intPart, fracPart, decimal.trueValueVsDigits)
    intPart = rounded.first
    fracPart = rounded.second
    // Rounding can carry the mantissa to 10, which needs renormalising back into [1, 10).
    if (intPart.length > 1) {
      adjusted += 1
      val shifted = intPart + fracPart
      intPart = shifted.substring(0, 1)
      fracPart = shifted.substring(1).let { it.substring(0, minOf(it.length, MAX_FRACTION_DIGITS)) }
    }
    return sign + intPart + "." + trimFraction(fracPart) + "e" + adjusted
  }

  // Place the decimal point: the digits represent d0.d1d2... times ten to the exponent.
  val intPart: String
  val fracPart: String
  if (exponent >= 0) {
    val intLength = exponent + 1
    intPart = if (digits.length >= intLength) digits.substring(0, intLength)
    else digits + "0".repeat(intLength - digits.length)
    fracPart = if (digits.length > intLength) digits.substring(intLength) else ""
  } else {
    intPart = "0"
    fracPart = "0".repeat(-exponent - 1) + digits
  }
  val (roundedInt, roundedFrac) = roundHalfEven(intPart, fracPart, decimal.trueValueVsDigits)
  return sign + roundedInt + "." + trimFraction(roundedFrac)
}
