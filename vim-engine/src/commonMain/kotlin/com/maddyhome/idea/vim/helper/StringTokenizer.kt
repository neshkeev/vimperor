/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * A multiplatform stand-in for `java.util.StringTokenizer`.
 *
 * [delimiters] is a *set of characters*, any one of which ends a token, and runs of delimiters never
 * produce empty tokens: `"a\n\nb"` tokenizes to `a`, `b` - not `a`, ``, `b`. Leading and trailing
 * delimiters are dropped for the same reason. Callers rely on that, so this is deliberately not
 * `String.split`, which keeps the empties.
 *
 * The default [delimiters] are the JDK's: space, tab, newline, carriage return and form feed.
 */
open class StringTokenizer(private val string: String, private val delimiters: String = " \t\n\r\u000C") {
  private var position = 0

  private fun skipDelimiters(from: Int): Int {
    var i = from
    while (i < string.length && string[i] in delimiters) i++
    return i
  }

  open fun hasMoreTokens(): Boolean {
    position = skipDelimiters(position)
    return position < string.length
  }

  open fun nextToken(): String {
    position = skipDelimiters(position)
    if (position >= string.length) throw NoSuchElementException()
    val start = position
    while (position < string.length && string[position] !in delimiters) position++
    return string.substring(start, position)
  }
}
