/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * The index of the first occurrence of [target] as a contiguous sublist of this list, or -1.
 *
 * Matches `java.util.Collections.indexOfSubList`, including returning 0 for an empty [target].
 */
fun <T> List<T>.indexOfSubList(target: List<T>): Int {
  if (target.isEmpty()) return 0
  if (target.size > size) return -1
  outer@ for (start in 0..(size - target.size)) {
    for (i in target.indices) {
      if (this[start + i] != target[i]) continue@outer
    }
    return start
  }
  return -1
}
