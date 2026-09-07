/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.key.VimKeyCodes

/*
 * JS `RegExp` supports Unicode property escapes with the `u` flag, so the general category can be
 * asked of the platform rather than embedded as a table. One regex per category, built once.
 *
 * Note the categories are tested in a fixed order and the first match wins, which is safe because
 * General_Category is a partition - every codepoint is in exactly one.
 *
 * Caveat worth knowing: the JS engine's Unicode version is whatever the runtime ships, and need not
 * match the JDK's. They can therefore disagree about codepoints assigned in between. That is a real
 * divergence, but a far smaller one than having no answer at all.
 */
private val CATEGORY_PATTERNS: List<Pair<CharCategory, Regex>> by lazy {
  listOf(
    CharCategory.UPPERCASE_LETTER to "Lu",
    CharCategory.LOWERCASE_LETTER to "Ll",
    CharCategory.TITLECASE_LETTER to "Lt",
    CharCategory.MODIFIER_LETTER to "Lm",
    CharCategory.OTHER_LETTER to "Lo",
    CharCategory.NON_SPACING_MARK to "Mn",
    CharCategory.ENCLOSING_MARK to "Me",
    CharCategory.COMBINING_SPACING_MARK to "Mc",
    CharCategory.DECIMAL_DIGIT_NUMBER to "Nd",
    CharCategory.LETTER_NUMBER to "Nl",
    CharCategory.OTHER_NUMBER to "No",
    CharCategory.SPACE_SEPARATOR to "Zs",
    CharCategory.LINE_SEPARATOR to "Zl",
    CharCategory.PARAGRAPH_SEPARATOR to "Zp",
    CharCategory.CONTROL to "Cc",
    CharCategory.FORMAT to "Cf",
    CharCategory.PRIVATE_USE to "Co",
    CharCategory.SURROGATE to "Cs",
    CharCategory.DASH_PUNCTUATION to "Pd",
    CharCategory.START_PUNCTUATION to "Ps",
    CharCategory.END_PUNCTUATION to "Pe",
    CharCategory.CONNECTOR_PUNCTUATION to "Pc",
    CharCategory.OTHER_PUNCTUATION to "Po",
    CharCategory.INITIAL_QUOTE_PUNCTUATION to "Pi",
    CharCategory.FINAL_QUOTE_PUNCTUATION to "Pf",
    CharCategory.MATH_SYMBOL to "Sm",
    CharCategory.CURRENCY_SYMBOL to "Sc",
    CharCategory.MODIFIER_SYMBOL to "Sk",
    CharCategory.OTHER_SYMBOL to "So",
  ).map { (category, code) -> category to Regex("^\\p{General_Category=" + code + "}$") }
}

actual fun charCategoryOf(codepoint: Int): CharCategory {
  val text = toChars(codepoint).concatToString()
  for ((category, pattern) in CATEGORY_PATTERNS) {
    if (pattern.matches(text)) return category
  }
  // General_Category is a partition, so falling through means the codepoint is unassigned.
  return CharCategory.UNASSIGNED
}

/**
 * The nearest test available without Java's block tables: reject control characters, the sentinel,
 * the specials block, and code points Unicode has not assigned. See the `expect` for what this
 * decides differently.
 */
actual fun isPrintableChar(c: Char): Boolean {
  val code = c.code
  if (code <= 0x1F || code in 0x7F..0x9F) return false
  if (c == VimKeyCodes.CHAR_UNDEFINED) return false
  if (code in 0xFFF0..0xFFFF) return false
  return c.category != CharCategory.UNASSIGNED
}
