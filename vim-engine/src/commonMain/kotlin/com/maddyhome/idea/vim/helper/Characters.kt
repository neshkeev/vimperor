/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

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
