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

  /**
   * Walks a snapshot, and `remove` takes the element it last returned out of the live collection.
   *
   * `remove` used to throw, on the reasoning that removing during a walk is what copy-on-write
   * exists to avoid. That was wrong twice over. `ConcurrentLinkedDeque`, which this stands in for on
   * the JVM, supports it - so the two platforms disagreed. And `MutableCollection.removeAll {}` is
   * written in terms of it, which is how `VimListenersNotifier.unloadListeners` removes a plugin's
   * listeners: on JS that threw `UnsupportedOperationException` for any extension that had actually
   * registered one, and disabling such an extension was impossible.
   */
  override fun iterator(): MutableIterator<T> {
    val snapshot = items
    var cursor = 0
    var removable = false
    return object : MutableIterator<T> {
      override fun hasNext() = cursor < snapshot.size

      override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        removable = true
        return snapshot[cursor++]
      }

      override fun remove() {
        check(removable) { "remove() before next(), or twice for the same element" }
        removable = false
        remove(snapshot[cursor - 1])
      }
    }
  }
}

actual fun <T> concurrentCollectionOf(): MutableCollection<T> = CopyOnWriteCollection()

/** Copy-on-write for the same reason [CopyOnWriteCollection] is, and insertion-ordered as a result. */
private class CopyOnWriteSet<T> : AbstractMutableSet<T>() {
  private var items: Set<T> = emptySet()

  override val size: Int get() = items.size

  override fun contains(element: T): Boolean = element in items

  override fun add(element: T): Boolean {
    if (element in items) return false
    items = items + element
    return true
  }

  override fun remove(element: T): Boolean {
    if (element !in items) return false
    items = items - element
    return true
  }

  override fun clear() {
    items = emptySet()
  }

  /** Snapshot iteration with a working `remove`, for the reason [CopyOnWriteCollection] has one. */
  override fun iterator(): MutableIterator<T> {
    val snapshot = items.toList()
    var cursor = 0
    var removable = false
    return object : MutableIterator<T> {
      override fun hasNext() = cursor < snapshot.size

      override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        removable = true
        return snapshot[cursor++]
      }

      override fun remove() {
        check(removable) { "remove() before next(), or twice for the same element" }
        removable = false
        remove(snapshot[cursor - 1])
      }
    }
  }
}

actual fun <T> concurrentSetOf(): MutableSet<T> = CopyOnWriteSet()
