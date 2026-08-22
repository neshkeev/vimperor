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

/**
 * Whether [c] is a character that can sensibly be typed into a document: not a control character,
 * not the "no character" sentinel, and not one of the specials at the top of the BMP.
 *
 * Platform-specific because the JVM answers it with `Character.UnicodeBlock`, whose tables no other
 * runtime has. The two targets disagree on 1,427 BMP code points - the ones that sit inside a
 * defined block but have not been assigned a character - which the JVM calls printable and a
 * category-based test does not. None of them can arrive as a `keyChar` from a real keystroke, which
 * is the only thing this is asked about, and the JVM keeps its exact previous answer.
 */
expect fun isPrintableChar(c: Char): Boolean

/**
 * True if [c] is a space character as `java.lang.Character.isSpaceChar` defines it: a member of one
 * of the three Unicode separator categories.
 *
 * Not the same question as [isVimWhitespace], and the difference is not academic - `isSpaceChar`
 * accepts a non-breaking space and rejects tab and newline, while whitespace does the opposite.
 * Both are used, a few lines apart, in the word-motion code.
 */
fun isSpaceChar(c: Char): Boolean = when (c.category) {
  CharCategory.SPACE_SEPARATOR,
  CharCategory.LINE_SEPARATOR,
  CharCategory.PARAGRAPH_SEPARATOR,
  -> true
  else -> false
}

/**
 * True if [c] may appear after the first character of a Java identifier, as
 * `java.lang.Character.isJavaIdentifierPart` defines it. This backs Vim's `\i` and `[:ident:]`.
 *
 * Phase 0's spike approximated this as `isLetterOrDigit() || '_' || '$'` and flagged that the
 * approximation changes which characters `\i` matches - currency symbols and several Unicode
 * categories. This is the JDK's actual rule instead: a letter, one of six categories, or an
 * ignorable control character.
 */
fun isIdentifierPart(c: Char): Boolean {
  if (c.isLetter()) return true
  return when (c.category) {
    CharCategory.CURRENCY_SYMBOL,
    CharCategory.CONNECTOR_PUNCTUATION,
    CharCategory.DECIMAL_DIGIT_NUMBER,
    CharCategory.LETTER_NUMBER,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.NON_SPACING_MARK,
    -> true
    // `isIdentifierIgnorable`: the non-whitespace ISO controls, and the format characters.
    CharCategory.FORMAT -> true
    else -> c.code in 0x00..0x08 || c.code in 0x0E..0x1B || c.code in 0x7F..0x9F
  }
}
