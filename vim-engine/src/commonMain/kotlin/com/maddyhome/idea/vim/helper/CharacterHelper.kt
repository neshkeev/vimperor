/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.options.helpers.KeywordOptionHelper

/**
 * This helper class is used when working with various character level operations
 */
object CharacterHelper {
  /**
   * This returns the type of the supplied character. The logic is as follows:<br></br>
   * If the character is whitespace, `WHITESPACE` is returned.<br></br>
   * If the punctuation is being skipped (WORD motions), every non-blank character is `KEYWORD`.<br></br>
   * If the character is a letter, digit, or underscore, `KEYWORD` is returned.<br></br>
   * Otherwise `PUNCTUATION` is returned.
   *
   * @param ch                   The character to analyze
   * @param punctuationAsLetters True if punctuation is to be ignored, false if not
   * @return The type of the character
   */
  @JvmStatic
  fun charType(editor: VimEditor, ch: Char, punctuationAsLetters: Boolean): CharacterType {
    if (isVimWhitespace(ch)) return CharacterType.WHITESPACE

    // A WORD is a sequence of non-blank characters, separated with white space (:help WORD). Vim's cls() returns the
    // same class for every non-blank character when cls_bigword is set, so script boundaries must not split a WORD.
    if (punctuationAsLetters) return CharacterType.KEYWORD

    return if (ch in HIRAGANA) {
      CharacterType.HIRAGANA
    } else if (ch in KATAKANA) {
      CharacterType.KATAKANA
    } else if (isHalfWidthKatakanaLetter(ch)) {
      CharacterType.HALF_WIDTH_KATAKANA
    } else if (ch in CJK_UNIFIED_IDEOGRAPHS) {
      CharacterType.CJK_UNIFIED_IDEOGRAPHS
    } else if (KeywordOptionHelper.isKeyword(editor, ch)) {
      CharacterType.KEYWORD
    } else {
      CharacterType.PUNCTUATION
    }
  }

  fun isWhitespace(editor: VimEditor, ch: Char, isBig: Boolean): Boolean =
    charType(editor, ch, isBig) == CharacterType.WHITESPACE

  fun isInvisibleControlCharacter(codepoint: Int): Boolean {
    val category = charCategoryOf(codepoint)
    return category == CharCategory.CONTROL || category == CharCategory.FORMAT ||
      category == CharCategory.PRIVATE_USE || category == CharCategory.SURROGATE ||
      category == CharCategory.UNASSIGNED
  }

  fun isZeroWidthCharacter(codepoint: Int): Boolean =
    codepoint == 0xfeff || codepoint == 0x200b || codepoint == 0x200c || codepoint == 0x200d

  private fun isHalfWidthKatakanaLetter(ch: Char): Boolean = ch in '\uFF66'..'\uFF9F'

  // Unicode block ranges, taken from java.lang.Character.UnicodeBlock and pinned by
  // CharacterHelperTest so they cannot drift from it silently.
  private val HIRAGANA = '\u3040'..'\u309F'
  private val KATAKANA = '\u30A0'..'\u30FF'
  private val CJK_UNIFIED_IDEOGRAPHS = '\u4E00'..'\u9FFF'

  enum class CharacterType {
    KEYWORD, HIRAGANA, KATAKANA, HALF_WIDTH_KATAKANA, CJK_UNIFIED_IDEOGRAPHS, PUNCTUATION, WHITESPACE
  }
}
