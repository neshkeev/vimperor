/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.common
import com.maddyhome.idea.vim.common.concurrentCollectionOf
import com.maddyhome.idea.vim.common.concurrentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The collections every host keeps its listeners in.
 *
 * They exist because a listener may remove itself, or register another, while being notified - so
 * iteration has to walk a snapshot. The JVM gets that from `ConcurrentLinkedDeque`; JS has no
 * threads and gets it from a hand-written copy-on-write collection, which is the half that can be
 * wrong on its own.
 */
class ConcurrentCollectionsTest {

  @Test
  fun `test a listener can be removed again`() {
    val listeners: MutableCollection<String> = concurrentCollectionOf()
    listeners.add("one")
    listeners.add("two")

    assertTrue(listeners.remove("one"))

    assertEquals(listOf("two"), listeners.toList())
    assertFalse(listeners.remove("one"), "removing it twice is not an error")
  }

  @Test
  fun `test iteration is unaffected by removal during the walk`() {
    val listeners: MutableCollection<String> = concurrentCollectionOf()
    listeners.add("one")
    listeners.add("two")

    val seen = mutableListOf<String>()
    listeners.forEach {
      seen += it
      listeners.remove(it)
    }

    assertEquals(listOf("one", "two"), seen, "the walk sees the snapshot it started with")
    assertTrue(listeners.isEmpty())
  }

  /**
   * `removeAll {}` is written in terms of the iterator's own `remove`, and it is how
   * `VimListenersNotifier.unloadListeners` drops one plugin's listeners.
   *
   * The JS iterator used to throw on `remove`, so disabling any extension that had registered a
   * listener - `yankring` is one - failed with `UnsupportedOperationException` on that host and
   * worked on the JVM. Nothing caught it, because an extension with no listeners never reaches the
   * iterator: `removeAll` on an empty collection removes nothing.
   */
  @Test
  fun `test removeAll takes out only what the predicate names`() {
    val listeners: MutableCollection<String> = concurrentCollectionOf()
    listeners.add("keep")
    listeners.add("drop one")
    listeners.add("drop two")

    listeners.removeAll { it.startsWith("drop") }

    assertEquals(listOf("keep"), listeners.toList())
  }

  @Test
  fun `test removeAll on the set too`() {
    val owners: MutableSet<String> = concurrentSetOf()
    owners.add("keep")
    owners.add("drop")

    owners.removeAll { it == "drop" }

    assertEquals(setOf("keep"), owners)
  }

  @Test
  fun `test the set deduplicates and still removes`() {
    val owners: MutableSet<String> = concurrentSetOf()
    owners.add("a")
    assertFalse(owners.add("a"))

    assertTrue(owners.remove("a"))
    assertTrue(owners.isEmpty())
  }
}
