/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.intellij.vim.api.scopes.TextScope
import com.maddyhome.idea.vim.api.injector

class TextScopeImpl : TextScope {
  override fun matches(pattern: String, text: String, ignoreCase: Boolean): Boolean {
    return injector.regexpService.matches(pattern, text, ignoreCase)
  }

  override fun getAllMatches(text: String, pattern: String): List<Pair<Int, Int>> {
    return injector.regexpService.getAllMatches(text, pattern)
  }

  override fun getNextCamelStartOffset(chars: CharSequence, startIndex: Int, count: Int): Int? {
    return injector.searchHelper.findNextCamelStart(chars, startIndex, count)
  }

  override fun getPreviousCamelStartOffset(chars: CharSequence, endIndex: Int, count: Int): Int? {
    return injector.searchHelper.findPreviousCamelStart(chars, endIndex, count)
  }

  override fun getNextCamelEndOffset(chars: CharSequence, startIndex: Int, count: Int): Int? {
    return injector.searchHelper.findNextCamelEnd(chars, startIndex, count)
  }

  override fun getPreviousCamelEndOffset(chars: CharSequence, endIndex: Int, count: Int): Int? {
    return injector.searchHelper.findPreviousCamelEnd(chars, endIndex, count)
  }
}
