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

/**
 * Moves [key] to the end of the map's iteration order, inserting it if absent.
 *
 * `java.util.SequencedMap.putLast`, which is Java 21 only. Order is user-visible here: it decides
 * the order of key/value pairs in options such as `'listchars'`.
 */
fun <K, V> MutableMap<K, V>.putLast(key: K, value: V) {
  remove(key)
  put(key, value)
}

/**
 * Moves [key] to the front of the map's iteration order, inserting it if absent.
 *
 * `java.util.SequencedMap.putFirst`. There is no way to prepend to a `LinkedHashMap`, so the map
 * is rebuilt - which is what the JDK does too.
 */
fun <K, V> MutableMap<K, V>.putFirst(key: K, value: V) {
  val existing = LinkedHashMap(this)
  clear()
  put(key, value)
  for ((k, v) in existing) if (k != key) put(k, v)
}

/** Lowercase hex, zero-padded to [width], as `String.format("%0<width>x", value)`. */
fun hexString(value: Int, width: Int): String = value.toUInt().toString(16).padStart(width, '0')

/** Octal, zero-padded to [width], as `String.format("%0<width>o", value)`. */
fun octString(value: Int, width: Int): String = value.toUInt().toString(8).padStart(width, '0')
