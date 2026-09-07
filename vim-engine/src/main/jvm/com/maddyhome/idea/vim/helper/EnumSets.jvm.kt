/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import java.util.EnumSet

/**
 * Returns a real [EnumSet], so ordinal iteration order and the bitset representation are
 * unchanged from before the multiplatform split.
 *
 * [all] is used only to recover the enum class without reflection on a `KClass`. `declaringClass`
 * rather than `javaClass` is deliberate: for an enum constant with a class body, `javaClass` is
 * the synthetic subclass (`Mode$1`) and [EnumSet.noneOf] rejects it.
 */
actual fun <T : Enum<T>> enumSetFrom(all: Array<T>, initial: Array<out T>): MutableSet<T> {
  if (all.isEmpty()) return LinkedHashSet()
  @Suppress("UNCHECKED_CAST")
  val declaring = (all[0] as java.lang.Enum<*>).declaringClass as Class<T>
  val set = EnumSet.noneOf(declaring)
  for (v in initial) set.add(v)
  return set
}
