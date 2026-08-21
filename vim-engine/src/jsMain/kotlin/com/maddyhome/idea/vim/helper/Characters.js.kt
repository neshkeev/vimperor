/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Unimplemented on purpose, and loudly.
 *
 * `Char.category` covers the basic plane, but there is no Kotlin/JS way to get the general category
 * of a supplementary codepoint, and JS has no bidi table at all. Returning a plausible-looking
 * default would make `:digraphs`, grapheme iteration and word motions silently wrong on this host
 * rather than obviously unfinished.
 *
 * The honest options are a generated Unicode table embedded in the artifact, or the browser's
 * `Intl`/`RegExp` Unicode property escapes (`\p{Cf}` and friends), which Node and every current
 * browser support. Neither is guesswork, and neither belongs in the commit that first made this
 * module compile.
 */
actual fun charCategoryOf(codepoint: Int): CharCategory =
  if (codepoint <= 0xFFFF) {
    codepoint.toChar().category
  } else {
    TODO("charCategoryOf for supplementary codepoints needs a Unicode table on JS")
  }

actual fun isRightToLeft(codepoint: Int): Boolean =
  TODO("isRightToLeft needs a Unicode bidi table on JS")
