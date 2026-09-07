/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * The JVM actual returns a real `EnumSet`, which iterates in ordinal order. There is no such thing
 * here, so ordinal order is reproduced explicitly: the set is backed by a `LinkedHashSet` filled in
 * the order [all] declares, which is the enum's ordinal order.
 *
 * That matters because mapping listings are rendered to the user in iteration order.
 */
actual fun <T : Enum<T>> enumSetFrom(all: Array<T>, initial: Array<out T>): MutableSet<T> {
  val wanted = initial.toHashSet()
  val result = OrdinalOrderedSet(all)
  for (value in all) if (value in wanted) result.add(value)
  return result
}

private class OrdinalOrderedSet<T : Enum<T>>(private val all: Array<T>) : AbstractMutableSet<T>() {
  private val present = HashSet<T>()

  override val size: Int get() = present.size

  override fun add(element: T): Boolean = present.add(element)

  override fun remove(element: T): Boolean = present.remove(element)

  override fun contains(element: T): Boolean = present.contains(element)

  override fun clear() = present.clear()

  // Always ordinal order, whatever order things were added in.
  override fun iterator(): MutableIterator<T> {
    val ordered = all.filter { it in present }
    var cursor = 0
    return object : MutableIterator<T> {
      private var last: T? = null
      override fun hasNext() = cursor < ordered.size
      override fun next(): T = ordered[cursor++].also { last = it }
      override fun remove() {
        present.remove(last ?: throw IllegalStateException())
      }
    }
  }
}
