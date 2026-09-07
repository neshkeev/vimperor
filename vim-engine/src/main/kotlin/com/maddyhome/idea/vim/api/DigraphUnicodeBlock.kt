/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

/**
 * The Unicode blocks `:digraphs` prints a header for.
 *
 * Only these exist here, and that is enough: the listing code assigns `previousUnicodeBlock` only
 * inside the branch that has already checked the block has a header name, so a block without one
 * can never be observed. Anything outside these ranges is [of]-null and prints no header, which is
 * exactly what `Character.UnicodeBlock.of` plus a failed map lookup did.
 *
 * The ranges are generated from `java.lang.Character.UnicodeBlock` rather than transcribed, and
 * `DigraphUnicodeBlockTest` asserts codepoint-by-codepoint that they still agree with it.
 */
enum class DigraphUnicodeBlock(val start: Int, val end: Int) {
  ARABIC(0x0600, 0x06FF),
  ARROWS(0x2190, 0x21FF),
  BASIC_LATIN(0x0000, 0x007F),
  BLOCK_ELEMENTS(0x2580, 0x259F),
  BOPOMOFO(0x3100, 0x312F),
  BOX_DRAWING(0x2500, 0x257F),
  CJK_SYMBOLS_AND_PUNCTUATION(0x3000, 0x303F),
  CONTROL_PICTURES(0x2400, 0x243F),
  CURRENCY_SYMBOLS(0x20A0, 0x20CF),
  CYRILLIC(0x0400, 0x04FF),
  DINGBATS(0x2700, 0x27BF),
  ENCLOSED_CJK_LETTERS_AND_MONTHS(0x3200, 0x32FF),
  GENERAL_PUNCTUATION(0x2000, 0x206F),
  GEOMETRIC_SHAPES(0x25A0, 0x25FF),
  GREEK(0x0370, 0x03FF),
  GREEK_EXTENDED(0x1F00, 0x1FFF),
  HEBREW(0x0590, 0x05FF),
  HIRAGANA(0x3040, 0x309F),
  KATAKANA(0x30A0, 0x30FF),
  LATIN_1_SUPPLEMENT(0x0080, 0x00FF),
  LATIN_EXTENDED_ADDITIONAL(0x1E00, 0x1EFF),
  LETTERLIKE_SYMBOLS(0x2100, 0x214F),
  MATHEMATICAL_OPERATORS(0x2200, 0x22FF),
  MISCELLANEOUS_SYMBOLS(0x2600, 0x26FF),
  MISCELLANEOUS_TECHNICAL(0x2300, 0x23FF),
  NUMBER_FORMS(0x2150, 0x218F),
  SUPERSCRIPTS_AND_SUBSCRIPTS(0x2070, 0x209F),
  ;

  companion object {
    /** The block [codepoint] falls in, or null if it is not one this table covers. */
    fun of(codepoint: Int): DigraphUnicodeBlock? = entries.firstOrNull { codepoint in it.start..it.end }
  }
}
