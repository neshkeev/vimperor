/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.helper.MAX_CODE_POINT
import com.maddyhome.idea.vim.helper.charCategoryOf
import com.maddyhome.idea.vim.helper.charCount
import com.maddyhome.idea.vim.helper.codePointAt
import com.maddyhome.idea.vim.helper.codePointBefore
import com.maddyhome.idea.vim.helper.codePointCount
import com.maddyhome.idea.vim.helper.isRightToLeft
import com.maddyhome.idea.vim.helper.isSupplementaryCodePoint
import com.maddyhome.idea.vim.helper.isVimWhitespace
import com.maddyhome.idea.vim.helper.toChars

/**
 * Whitespace as `java.lang.Character.isWhitespace` defines it, which is what Vim's character
 * classification has always used here.
 *
 * Deliberately not Kotlin's [Char.isWhitespace], which is *broader*: it also accepts the three
 * non-breaking space characters, because it is defined as `isWhitespace() || isSpaceChar()`.
 * Widening this would reclassify a non-breaking space from punctuation to whitespace and change
 * where `w` and `b` stop. `CharacterHelperTest` pins the two definitions apart.
 */
fun isVimWhitespace(ch: Char): Boolean =
  ch.isWhitespace() && ch != '\u00A0' && ch != '\u2007' && ch != '\u202F'

/**
 * The Unicode general category of a codepoint, including codepoints outside the basic plane.
 *
 * Kotlin's [Char.category] only reaches a single `Char`, so supplementary codepoints need the
 * platform.
 */
expect fun charCategoryOf(codepoint: Int): CharCategory

/** The largest valid Unicode codepoint, as `MAX_CODE_POINT`. */
const val MAX_CODE_POINT: Int = 0x10FFFF

/** True if [codepoint] needs a surrogate pair, as `Character.isSupplementaryCodePoint`. */
fun isSupplementaryCodePoint(codepoint: Int): Boolean = codepoint in 0x10000..MAX_CODE_POINT

/** The number of `Char`s [codepoint] occupies: 2 for a supplementary codepoint, 1 otherwise. */
fun charCount(codepoint: Int): Int = if (isSupplementaryCodePoint(codepoint)) 2 else 1

private fun combineSurrogates(high: Char, low: Char): Int =
  ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/**
 * The codepoint starting at [index], as `Character.codePointAt`.
 *
 * An unpaired surrogate is returned as itself, which is what the JDK does - it does not throw and
 * does not substitute a replacement character.
 */
fun codePointAt(text: CharSequence, index: Int): Int {
  val high = text[index]
  if (high.isHighSurrogate() && index + 1 < text.length) {
    val low = text[index + 1]
    if (low.isLowSurrogate()) return combineSurrogates(high, low)
  }
  return high.code
}

/** The codepoint ending just before [index], as `Character.codePointBefore`. */
fun codePointBefore(text: CharSequence, index: Int): Int {
  val low = text[index - 1]
  if (low.isLowSurrogate() && index - 2 >= 0) {
    val high = text[index - 2]
    if (high.isHighSurrogate()) return combineSurrogates(high, low)
  }
  return low.code
}

/** The number of codepoints between [beginIndex] and [endIndex], as `Character.codePointCount`. */
fun codePointCount(text: CharSequence, beginIndex: Int, endIndex: Int): Int {
  var count = 0
  var i = beginIndex
  while (i < endIndex) {
    i += charCount(codePointAt(text, i))
    count++
  }
  return count
}

/** [codepoint] as one or two `Char`s, as `Character.toChars`. */
fun toChars(codepoint: Int): CharArray =
  if (isSupplementaryCodePoint(codepoint)) {
    val offset = codepoint - 0x10000
    charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar())
  } else {
    charArrayOf(codepoint.toChar())
  }

/**
 * True if [codepoint] is written right-to-left.
 *
 * Narrower than the `Character.getDirectionality` it replaces, on purpose: the engine only ever
 * asks this one question, and exposing a directionality byte would oblige every host to reproduce
 * the whole Unicode bidi table rather than the part that is used.
 */
expect fun isRightToLeft(codepoint: Int): Boolean

/**
 * True if [codepoint] is a letter, as `Character.isLetter(int)`.
 *
 * The `Char` overload has a Kotlin equivalent in `Char.isLetter()`; this is the codepoint one,
 * which does not, and which is what `'isalpha'` handling needs for supplementary characters. The
 * five categories are the ones the JDK tests.
 */
fun isLetterCodePoint(codepoint: Int): Boolean = when (charCategoryOf(codepoint)) {
  CharCategory.UPPERCASE_LETTER,
  CharCategory.LOWERCASE_LETTER,
  CharCategory.TITLECASE_LETTER,
  CharCategory.MODIFIER_LETTER,
  CharCategory.OTHER_LETTER,
  -> true
  else -> false
}
