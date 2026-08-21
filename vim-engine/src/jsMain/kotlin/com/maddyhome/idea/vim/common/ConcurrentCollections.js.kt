/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

/**
 * JS has no threads, so there is nothing to exclude - but the second requirement in the `expect`
 * still applies: a listener may remove itself, or register another, while being notified. That is
 * reentrancy, not concurrency, and a plain `ArrayList` would throw on it.
 *
 * So this is copy-on-write: iteration walks a snapshot and is unaffected by mutation during the
 * walk, which is the property `ConcurrentLinkedDeque` provides on the JVM.
 */
private class CopyOnWriteCollection<T> : AbstractMutableCollection<T>() {
  private var items: List<T> = emptyList()

  override val size: Int get() = items.size

  override fun add(element: T): Boolean {
    items = items + element
    return true
  }

  override fun remove(element: T): Boolean {
    val index = items.indexOf(element)
    if (index < 0) return false
    items = items.filterIndexed { i, _ -> i != index }
    return true
  }

  override fun clear() {
    items = emptyList()
  }

  override fun iterator(): MutableIterator<T> {
    val snapshot = items
    var cursor = 0
    return object : MutableIterator<T> {
      override fun hasNext() = cursor < snapshot.size
      override fun next(): T = snapshot[cursor++]
      override fun remove() = throw UnsupportedOperationException("remove during iteration")
    }
  }
}

actual fun <T> concurrentCollectionOf(): MutableCollection<T> = CopyOnWriteCollection()
